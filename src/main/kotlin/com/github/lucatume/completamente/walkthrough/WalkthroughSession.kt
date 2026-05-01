package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.services.DebugLog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Cursor
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.KeyStroke

/**
 * Install an AWT mouse-motion listener on [editor]'s content component that flips the cursor
 * to [Cursor.HAND_CURSOR] while the mouse is inside the bounds of the inlay returned by
 * [inlayProvider], and back to [Cursor.TEXT_CURSOR] on exit. Listener is removed when [parent]
 * is disposed.
 *
 * Whole-inlay scope (not per-chevron): the body advertises its entire surface as interactive
 * (clicks anywhere inside route through chevron hit-tests), so the cursor reflects that contract.
 */
internal fun installInlayHoverCursor(
    editor: Editor,
    inlayProvider: () -> com.intellij.openapi.editor.Inlay<*>?,
    parent: Disposable,
) {
    val cc = editor.contentComponent
    val listener = object : MouseMotionAdapter() {
        private var lastInside = false
        override fun mouseMoved(e: MouseEvent) {
            val bounds = inlayProvider()?.bounds
            val inside = bounds != null && bounds.contains(e.x, e.y)
            if (inside == lastInside) return
            lastInside = inside
            cc.cursor = Cursor.getPredefinedCursor(
                if (inside) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR
            )
        }
    }
    cc.addMouseMotionListener(listener)
    Disposer.register(parent, Disposable { cc.removeMouseMotionListener(listener) })
}

/**
 * Resolved view of a [WalkthroughStep] — what the IDE needs at navigation time.
 */
internal data class ResolvedStep(
    val step: WalkthroughStep,
    val virtualFile: VirtualFile,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * Outcome of resolving a step against the current project state. Drives the cascade in
 * [WalkthroughSession.advance].
 */
internal sealed class Resolution {
    data class Ok(val resolved: ResolvedStep) : Resolution()
    data class Skip(val reason: String) : Resolution()
}

/**
 * IDE-coupled session: ties the pure [WalkthroughNavigator] to the IDE surfaces that render
 * each step (file open, range highlighter, popup, listeners, ESC handler). One instance per
 * active walkthrough.
 *
 * Lifecycle is via [Disposable]: `Disposer.register(walkthroughService, this)` so project
 * close runs cleanup. [dispose] is idempotent and clears the active highlighter, popup,
 * listeners, and ESC handler in one shot.
 */
class WalkthroughSession private constructor(
    private val project: Project,
    private val navigator: WalkthroughNavigator,
    private val triggerEditor: Editor
) : Disposable {

    @Volatile private var disposed = false
    private var currentEditor: Editor? = null
    private var currentRangeMarker: com.intellij.openapi.editor.RangeMarker? = null
    private var currentInlay: NarrationInlay? = null
    /** Disposable parented to *this* session that owns the per-step listeners + inlay + ESC. Cleared on every step transition. */
    private var stepDisposable: Disposable? = null

    init {
        navigator.onDispose { dispose() }
        // Listen for tab close on the active step's file → dispose. Listener is parented to
        // the session so it's auto-removed on dispose.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val active = currentEditor ?: return
                    if (active.virtualFile == file) {
                        notifyError("Walkthrough closed: the active step's file was closed.")
                        dispose()
                    }
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    // Selection switched to a different file → dispose the popup + remove
                    // the highlighter, but keep the session alive so navigation back to the
                    // step's file rebuilds them.
                    onSelectionChanged()
                }
            }
        )

        // Listen for VFS changes that touch the active step's file. The RangeHighlighter
        // tracks edits natively but external VFS-level changes (git checkout, file deletion)
        // can leave the highlighter or document in a stale state — re-render so clamp/invalid
        // state is recomputed.
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                override fun after(events: List<com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                    val active = currentEditor ?: return
                    val activeFile = active.virtualFile ?: return
                    if (events.any { it.file == activeFile }) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!disposed) refreshCurrentStep()
                        }
                    }
                }
            }
        )
    }

    private fun registerEscHandlerOn(editor: Editor, parent: Disposable) {
        val escAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                dispose()
            }
        }
        escAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
            editor.component,
            parent
        )
    }

    fun start() {
        showCurrentStep(navSkippedAggregateFromInit = navigator.lastSkipped)
    }

    /** Re-render the current step in place — used when external state may have changed (VFS refresh). */
    private fun refreshCurrentStep() {
        if (disposed) return
        showCurrentStep(navSkippedAggregateFromInit = emptyList())
    }

    private fun onSelectionChanged() {
        if (disposed) return
        val active = currentEditor ?: return
        val activeFile = active.virtualFile ?: return
        val nowSelected = FileEditorManager.getInstance(project).selectedTextEditor
        // Same selected editor as before → ignore.
        if (nowSelected === active) return
        val nowFile = nowSelected?.virtualFile
        if (nowFile == activeFile) {
            // Re-anchor to the new (selected) editor of the same file.
            tearDownCurrentSurfaces()
            currentEditor = nowSelected
            renderResolved(currentResolved() ?: return)
        } else {
            // Different file selected — hide popup + highlighter; session stays alive.
            tearDownCurrentSurfaces()
            currentEditor = null
        }
    }

    private fun currentResolved(): ResolvedStep? {
        return ReadAction.compute<ResolvedStep?, RuntimeException> {
            when (val r = resolveStep(project, navigator.current)) {
                is Resolution.Ok -> r.resolved
                is Resolution.Skip -> null
            }
        }
    }

    /**
     * Move forward one step (cascades through unresolvables via the navigator). Renders the
     * destination and surfaces an aggregated notification if any steps were skipped.
     */
    fun goNext() {
        if (disposed) return
        navigator.goNext()
        if (navigator.lastNavReachedEnd && currentEditor != null) {
            // No further resolvable step. Stay put; surface a one-shot notification only if
            // we actually skipped something on the way to the dead end.
            if (navigator.lastSkipped.isNotEmpty()) {
                notifyAggregatedSkip(navigator.lastSkipped, prefix = "Reached end")
            }
            return
        }
        showCurrentStep(navSkippedAggregateFromInit = navigator.lastSkipped)
    }

    fun goPrev() {
        if (disposed) return
        navigator.goPrev()
        showCurrentStep(navSkippedAggregateFromInit = emptyList())
    }

    fun goFirst() {
        if (disposed) return
        navigator.goFirst()
        showCurrentStep(navSkippedAggregateFromInit = emptyList())
    }

    fun goLast() {
        if (disposed) return
        navigator.goLast()
        showCurrentStep(navSkippedAggregateFromInit = navigator.lastSkipped)
    }

    /**
     * Render whichever step the navigator currently points at. Builds a fresh popup,
     * adds a fresh highlighter on whatever editor now hosts the file, and tears down the
     * previous step's surfaces.
     */
    private fun showCurrentStep(navSkippedAggregateFromInit: List<WalkthroughStep>) {
        if (disposed) return
        if (navSkippedAggregateFromInit.isNotEmpty()) {
            notifyAggregatedSkip(navSkippedAggregateFromInit)
        }
        val resolved = currentResolved() ?: run {
            // The navigator's resolvable filter should have prevented this, but be defensive.
            notifyError("Walkthrough step is unresolvable: ${navigator.current.range.file}")
            return
        }
        renderResolved(resolved)
    }

    private fun renderResolved(resolved: ResolvedStep) {
        tearDownCurrentSurfaces()

        // Open the file (focus=true ensures we get a real Editor back synchronously and
        // anchor to the correct frame on multi-monitor setups).
        val descriptor = OpenFileDescriptor(
            project, resolved.virtualFile,
            resolved.step.range.startLine,
            resolved.step.range.startCol
        )
        val editor: Editor = FileEditorManager.getInstance(project)
            .openTextEditor(descriptor, /* focusEditor = */ true) ?: run {
                notifyError("Could not open ${resolved.step.range.file}")
                return
            }
        currentEditor = editor

        // Wrap the per-step listeners + popup in a fresh Disposable parented to the session.
        val stepDisp = Disposer.newDisposable("WalkthroughSession.step")
        Disposer.register(this, stepDisp)
        stepDisposable = stepDisp

        // ESC is editor-component-scoped (close from anywhere in the editor frame).
        registerEscHandlerOn(editor, stepDisp)

        // Spec step 6: scroll/fold/highlight under a single invokeLater so the editor is
        // fully realized first.
        ApplicationManager.getApplication().invokeLater(Runnable {
            if (disposed || stepDisp !== stepDisposable) return@Runnable
            if (editor.isDisposed) return@Runnable

            editor.foldingModel.runBatchFoldingOperation {
                for (region in editor.foldingModel.allFoldRegions) {
                    if (region.startOffset < resolved.endOffset &&
                        region.endOffset > resolved.startOffset && !region.isExpanded
                    ) {
                        region.isExpanded = true
                    }
                }
            }

            editor.scrollingModel.scrollTo(
                LogicalPosition(resolved.step.range.startLine, resolved.step.range.startCol),
                ScrollType.CENTER
            )
            // Keep `startLine + 5` in view so the user has lookahead into the highlighted code.
            // MAKE_VISIBLE is a no-op when already in viewport; on a short viewport it nudges
            // the scroll just enough to reveal the lookahead line.
            val maxLine = (editor.document.lineCount - 1).coerceAtLeast(0)
            val lookaheadLine = (resolved.step.range.startLine + 5).coerceAtMost(maxLine)
            editor.scrollingModel.scrollTo(
                LogicalPosition(lookaheadLine, 0),
                ScrollType.MAKE_VISIBLE
            )

            // RangeMarker tracks the range across document edits; `isValid == false` means the
            // range was deleted from under us. Used as the "(range no longer valid)" footer
            // signal — `RangeMarker.isValid` does NOT track text-content edits, only deletion.
            val marker = editor.document.createRangeMarker(resolved.startOffset, resolved.endOffset)
            currentRangeMarker = marker
            Disposer.register(stepDisp, Disposable {
                try {
                    if (marker.isValid) marker.dispose()
                } catch (e: IllegalStateException) {
                    DebugLog.log("WalkthroughSession ignoring marker disposal on disposed document: ${e.message}")
                }
            })

            val footer = if (marker.isValid) null else "(range no longer valid)"
            // Anchor the wedge on the first visible (non-whitespace) character of the
            // highlighted range's first line — not the literal `startCol`. The walkthrough
            // agent often emits `range="L:1-..."` for indented code; honoring that literally
            // would point the wedge at the gutter instead of the code the user is reading.
            val anchorOffset = computeAnchorOffset(
                editor.document.charsSequence,
                resolved.startOffset,
                resolved.endOffset,
            )
            // Build nav state from navigator. Index discipline: NavButton.{FIRST,PREV,NEXT,LAST}.ordinal.
            val navEnabled = BooleanArray(4).also {
                it[NarrationInlay.NavButton.FIRST.ordinal] = navigator.canGoFirst()
                it[NarrationInlay.NavButton.PREV.ordinal]  = navigator.canGoPrev()
                it[NarrationInlay.NavButton.NEXT.ordinal]  = navigator.canGoNext()
                it[NarrationInlay.NavButton.LAST.ordinal]  = navigator.canGoLast()
            }
            val navCallbacks = arrayOf<() -> Unit>(
                { goFirst() }, { goPrev() }, { goNext() }, { goLast() },
            )
            currentInlay = NarrationInlay(
                editor = editor,
                rangeStartOffset = resolved.startOffset,
                narration = resolved.step.narration,
                stepCounter = "step ${navigator.currentIndex}/${navigator.totalReachable}",
                footerStatus = footer,
                anchorOffset = anchorOffset,
                parentDisposable = stepDisp,
                navEnabled = navEnabled,
                navCallbacks = navCallbacks,
            )
            if (currentInlay?.isAttached() != true) {
                DebugLog.log("WalkthroughSession: inlay refused at offset=${resolved.startOffset}; disposing session")
                dispose()
                return@Runnable
            }

            // Register chevron click router. BUTTON1-only; right/middle clicks fall through.
            // Listener parented to stepDisp → auto-removed on every step transition.
            // Hit-testing is bounds-based against the inlay directly (does not rely on
            // `EditorMouseEvent.inlay` populating for block-element inlays — observed unreliable
            // in 2024.3.6 for clicks at the body interior, despite the platform docs).
            editor.addEditorMouseListener(object : com.intellij.openapi.editor.event.EditorMouseListener {
                override fun mouseClicked(e: com.intellij.openapi.editor.event.EditorMouseEvent) {
                    if (e.mouseEvent.button != java.awt.event.MouseEvent.BUTTON1) return
                    val target = currentInlay ?: return
                    val bounds = target.attachedInlay?.bounds ?: return
                    val mx = e.mouseEvent.x
                    val my = e.mouseEvent.y
                    if (!bounds.contains(mx, my)) return
                    val localX = mx - bounds.x
                    val localY = my - bounds.y
                    target.renderer.routeClick(localX, localY)?.invoke()
                    e.consume()
                }
            }, stepDisp)

            // Whole-inlay hover → hand cursor. AWT-level listener on contentComponent fires after
            // EditorImpl's own cursor management, so our setCursor wins. Tracks transitions only
            // (no per-frame churn). Restores TEXT_CURSOR (the editor body's default) on exit so
            // the I-beam comes back when the mouse leaves the inlay.
            installInlayHoverCursor(editor, { currentInlay?.attachedInlay }, stepDisp)
        })
    }

    private fun tearDownCurrentSurfaces() {
        currentInlay = null
        currentRangeMarker = null
        val disp = stepDisposable
        stepDisposable = null
        // Disposer.dispose is idempotent for already-disposed disposables but logs a warning
        // under idea.is.internal=true. Catch only IllegalStateException — that's what the
        // platform throws on double-dispose. Wider Throwable would mask programming errors.
        if (disp != null) {
            try {
                Disposer.dispose(disp)
            } catch (e: IllegalStateException) {
                // already disposed by the parent Disposer chain — fine
                DebugLog.log("WalkthroughSession.tearDownCurrentSurfaces ignoring double-dispose: ${e.message}")
            }
        }
    }

    private fun notifyAggregatedSkip(skipped: List<WalkthroughStep>, prefix: String = "Skipped") {
        if (skipped.isEmpty()) return
        val n = skipped.size
        val reasons = skipped.joinToString(", ") { it.range.file }
        val title = "$prefix $n unresolvable step${if (n == 1) "" else "s"}"
        NotificationGroupManager.getInstance()
            .getNotificationGroup("completamente")
            .createNotification("$title: $reasons", NotificationType.WARNING)
            .notify(project)
    }

    private fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("completamente")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        // tearDownCurrentSurfaces is idempotent — once stepDisposable is disposed (either by
        // us or by the parent Disposer chain), subsequent calls no-op. Run it inline so the
        // popup + highlighter come down immediately, even if dispose was invoked off-EDT.
        if (ApplicationManager.getApplication().isDispatchThread) {
            tearDownCurrentSurfaces()
        } else {
            ApplicationManager.getApplication().invokeLater { tearDownCurrentSurfaces() }
        }
        currentEditor = null
        // Mirror to navigator (in case external code calls dispose directly).
        if (!navigator.isDisposed) navigator.dispose()
    }

    companion object {
        /**
         * Resolve [step] against the project. Returns [Resolution.Ok] with the file and clamped
         * offsets; [Resolution.Skip] with a human-readable reason otherwise.
         *
         * Must be called inside a read action.
         */
        internal fun resolveStep(project: Project, step: WalkthroughStep): Resolution {
            val vf = findFileInContentRoots(project, step.range.file)
                ?: return Resolution.Skip("file not found")
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) {
                return Resolution.Skip("outside project")
            }
            if (vf.fileType.isBinary) {
                return Resolution.Skip("binary file")
            }
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
                ?: return Resolution.Skip("no document")
            val (startOffset, endOffset) = clampToDocument(doc, step.range)
                ?: return Resolution.Skip("range out of bounds")
            return Resolution.Ok(ResolvedStep(step, vf, startOffset, endOffset))
        }

        private fun findFileInContentRoots(project: Project, relPath: String): VirtualFile? {
            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                val file = root.findFileByRelativePath(relPath)
                if (file != null) return file
            }
            return null
        }

        private fun clampToDocument(
            doc: com.intellij.openapi.editor.Document,
            range: StepRange
        ): Pair<Int, Int>? {
            val maxLine = (doc.lineCount - 1).coerceAtLeast(0)
            val startLine = range.startLine.coerceIn(0, maxLine)
            val endLine = range.endLine.coerceIn(0, maxLine)
            val startLineLength = doc.getLineEndOffset(startLine) - doc.getLineStartOffset(startLine)
            val endLineLength = doc.getLineEndOffset(endLine) - doc.getLineStartOffset(endLine)
            val startCol = range.startCol.coerceIn(0, startLineLength)
            val endCol = range.endCol.coerceIn(0, endLineLength)
            val startOffset = doc.getLineStartOffset(startLine) + startCol
            val endOffset = doc.getLineStartOffset(endLine) + endCol
            if (endOffset <= startOffset) return null
            return startOffset to endOffset
        }

        /**
         * Build a session over [walkthrough] for [project], starting in [triggerEditor].
         * Performs the spec's first-step resolvability check (rebuild) and returns null if
         * every step is unresolvable.
         */
        fun build(
            project: Project,
            walkthrough: Walkthrough,
            triggerEditor: Editor
        ): WalkthroughSession? {
            val rebuilt = ReadAction.compute<Walkthrough?, RuntimeException> {
                WalkthroughNavigator.rebuildFromFirstResolvable(walkthrough) { step ->
                    resolveStep(project, step) is Resolution.Ok
                }
            } ?: return null
            val resolvable: (WalkthroughStep) -> Boolean = { step ->
                ReadAction.compute<Boolean, RuntimeException> {
                    resolveStep(project, step) is Resolution.Ok
                }
            }
            val nav = WalkthroughNavigator(rebuilt, resolvable)
            return WalkthroughSession(project, nav, triggerEditor)
        }

        // ESC fall-through helper for callers that may want it.
        @Suppress("unused")
        internal fun fallThroughToEditorEscape(e: AnActionEvent) {
            ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_ESCAPE)?.actionPerformed(e)
        }
    }
}
