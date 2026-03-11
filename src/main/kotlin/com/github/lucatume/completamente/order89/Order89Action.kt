package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.services.SettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.awt.Color
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import javax.swing.KeyStroke
import javax.swing.Timer
import kotlin.math.sin

private val ORDER89_NEON_PINK = Color(255, 16, 240)
private val ORDER89_CYAN = Color(0, 255, 255)

internal fun lerpColor(a: Color, b: Color, t: Double): Color {
    val ct = t.coerceIn(0.0, 1.0)
    return Color(
        (a.red + (b.red - a.red) * ct).toInt(),
        (a.green + (b.green - a.green) * ct).toInt(),
        (a.blue + (b.blue - a.blue) * ct).toInt()
    )
}

internal fun truncatePrompt(prompt: String, maxLength: Int = 60): String {
    val collapsed = prompt.replace('\n', ' ').replace('\r', ' ').trim()
    if (collapsed.length <= maxLength) return collapsed
    return collapsed.substring(0, maxLength) + "..."
}

data class Order89StatusDisplay(
    val range: RangeMarker,
    val symbolRange: RangeMarker,
    val highlighters: List<RangeHighlighter>,
    val timer: Timer
)

data class Order89Session(
    val process: Process,
    val future: Future<Order89Result>,
    val statusDisplay: Order89StatusDisplay?,
    val range: RangeMarker
)

class Order89Action : AnAction() {

    companion object {
        internal val SESSIONS_KEY = Key.create<ArrayDeque<Order89Session>>("order89.sessions")
        private val ESC_ACTION_KEY = Key.create<AnAction>("order89.escAction")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd
        val targetLine = editor.document.getLineNumber(selectionStart)

        val dialog = Order89Dialog(editor.component)
        if (!dialog.showAndWait() || dialog.promptText.isBlank()) return

        val order89Command = SettingsState.getInstance().order89Command
        if (order89Command.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("completamente")
                .createNotification("Order 89 command not configured", NotificationType.WARNING)
                .notify(project)
            return
        }

        val filePath = psiFile.virtualFile?.path ?: ""

        // Capture file content BEFORE inserting status lines.
        val request = Order89Request(
            commandTemplate = order89Command,
            prompt = dialog.promptText,
            filePath = filePath,
            fileContent = editor.document.text,
            language = psiFile.language.id,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            workingDirectory = project.basePath ?: "."
        )

        // Create selection range marker BEFORE inserting status lines.
        val rangeMarker = editor.document.createRangeMarker(selectionStart, selectionEnd)
        rangeMarker.isGreedyToRight = true

        // Insert status lines as real document text above the selection.
        val insertionOffset = editor.document.getLineStartOffset(targetLine)
        val statusDisplay = insertStatusLines(editor, insertionOffset, dialog.promptText)

        val (process, future) = Order89Executor.execute(request)

        val session = Order89Session(process, future, statusDisplay, rangeMarker)
        val sessions = editor.getUserData(SESSIONS_KEY) ?: ArrayDeque<Order89Session>().also {
            editor.putUserData(SESSIONS_KEY, it)
        }
        val wasEmpty = sessions.isEmpty()
        sessions.push(session)

        if (wasEmpty) {
            val escAction = Order89EscAction(editor, this)
            escAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                editor.component
            )
            editor.putUserData(ESC_ACTION_KEY, escAction)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = future.get()
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    if (result.success) {
                        // 1. Delete status lines undo-transparently → selection range shifts back to original offsets.
                        removeStatusDisplay(editor, session.statusDisplay)
                        // 2. Replace selection (undoable) → greedy range expands to new text length.
                        WriteCommandAction.runWriteCommandAction(project, "Order 89", null, {
                            if (!session.range.isValid) return@runWriteCommandAction
                            editor.document.replaceString(session.range.startOffset, session.range.endOffset, result.output)
                            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                            val committedPsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                            if (committedPsiFile != null && session.range.isValid) {
                                CodeStyleManager.getInstance(project).reformatText(committedPsiFile, session.range.startOffset, session.range.endOffset)
                            }
                        })
                    } else {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("completamente")
                            .createNotification(result.output, NotificationType.ERROR)
                            .notify(project)
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled by user via ESC.
            } catch (ex: Exception) {
                // Covers process I/O errors, unexpected executor failures, etc.
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("completamente")
                        .createNotification(ex.message ?: "Unknown error", NotificationType.ERROR)
                        .notify(project)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    val deque = editor.getUserData(SESSIONS_KEY)
                    if (deque?.remove(session) == true) {
                        removeStatusDisplay(editor, session.statusDisplay)
                        session.range.dispose()
                    }
                    if (deque?.isEmpty() == true) {
                        unregisterEsc(editor)
                    }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    internal fun unregisterEsc(editor: Editor) {
        editor.getUserData(ESC_ACTION_KEY)?.unregisterCustomShortcutSet(editor.component)
        editor.putUserData(ESC_ACTION_KEY, null)
    }

    /**
     * Stops the timer, removes highlighters, deletes status text from the document (undo-transparently),
     * and disposes the status range. Safe to call when the display has already been cleaned up (idempotent):
     * the timer stop and highlighter removal are no-ops if already done, and the range validity check
     * guards the document deletion.
     */
    internal fun removeStatusDisplay(editor: Editor, display: Order89StatusDisplay?) {
        if (display == null) return
        display.timer.stop()
        display.highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.symbolRange.dispose()
        if (display.range.isValid) {
            ApplicationManager.getApplication().runWriteAction {
                CommandProcessor.getInstance().runUndoTransparentAction {
                    if (display.range.isValid) {
                        editor.document.deleteString(display.range.startOffset, display.range.endOffset)
                    }
                }
            }
        }
        display.range.dispose()
    }

    private fun insertStatusLines(editor: Editor, offset: Int, prompt: String): Order89StatusDisplay? {
        val truncated = truncatePrompt(prompt)
        val lineEnd = editor.document.getLineEndOffset(editor.document.getLineNumber(offset))
        val lineText = editor.document.getText(TextRange(offset, lineEnd))
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val statusLine1 = "$indent\u2726 Executing..."
        val statusLine2 = "$indent  $truncated"
        val statusText = "$statusLine1\n$statusLine2\n"

        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                editor.document.insertString(offset, statusText)
            }
        }

        // Range covers the full inserted text including trailing newline, so deletion removes all of it.
        val statusRange = editor.document.createRangeMarker(offset, offset + statusText.length)
        val symbolRange = editor.document.createRangeMarker(offset + indent.length, offset + indent.length + 1)

        val symbols = charArrayOf('\u2726', '\u2727', '\u2736', '\u2737', '\u2738', '\u2739')
        var symbolIndex = 0

        val markup = editor.markupModel
        val attrs1 = TextAttributes().apply {
            foregroundColor = ORDER89_NEON_PINK
            fontType = Font.ITALIC
        }
        val attrs2 = TextAttributes().apply {
            foregroundColor = ORDER89_NEON_PINK
            fontType = Font.ITALIC
        }

        val line1End = offset + statusLine1.length
        // +1 skips the newline between lines.
        val line2Start = line1End + 1
        val line2End = line2Start + statusLine2.length

        // LAST ensures status text styling takes priority over editor default colors.
        val h1 = markup.addRangeHighlighter(
            offset, line1End,
            HighlighterLayer.LAST,
            attrs1,
            HighlighterTargetArea.EXACT_RANGE
        )
        val h2 = markup.addRangeHighlighter(
            line2Start, line2End,
            HighlighterLayer.LAST,
            attrs2,
            HighlighterTargetArea.EXACT_RANGE
        )
        val highlighters = listOf(h1, h2)
        val attrsList = listOf(attrs1, attrs2)

        var frameCount = 0
        val timer = Timer(100) {
            // Wraps at 1000 to prevent unbounded growth; at 100ms intervals this cycles every ~100s.
            frameCount = (frameCount + 1) % 1000
            val t = (sin(frameCount * 0.5) + 1.0) / 2.0
            val color = lerpColor(ORDER89_NEON_PINK, ORDER89_CYAN, t)
            attrsList.forEach { it.foregroundColor = color }
            // Rotate star symbol every 3rd frame (~300ms).
            if (frameCount % 3 == 0 && symbolRange.isValid) {
                symbolIndex = (symbolIndex + 1) % symbols.size
                ApplicationManager.getApplication().runWriteAction {
                    CommandProcessor.getInstance().runUndoTransparentAction {
                        if (symbolRange.isValid) {
                            editor.document.replaceString(
                                symbolRange.startOffset, symbolRange.endOffset,
                                symbols[symbolIndex].toString()
                            )
                        }
                    }
                }
            }
            if (!editor.isDisposed) editor.contentComponent.repaint()
        }
        timer.start()

        return Order89StatusDisplay(statusRange, symbolRange, highlighters, timer)
    }
}

/** Cursor-aware ESC handler that cancels the Order 89 session matching the caret position. */
private class Order89EscAction(
    private val editor: Editor,
    private val parent: Order89Action
) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (editor.isDisposed) return
        val deque = editor.getUserData(Order89Action.SESSIONS_KEY) ?: return
        val doc = editor.document
        val caretLine = editor.caretModel.logicalPosition.line

        // Match if the caret is on the selection lines OR on the status display lines.
        val match = deque.firstOrNull { s ->
            if (!s.range.isValid) return@firstOrNull false
            val onSelection = caretLine >= doc.getLineNumber(s.range.startOffset) &&
                caretLine <= doc.getLineNumber(s.range.endOffset)
            val onStatus = s.statusDisplay?.range?.let { r ->
                r.isValid &&
                    caretLine >= doc.getLineNumber(r.startOffset) &&
                    caretLine <= doc.getLineNumber(r.endOffset)
            } ?: false
            onSelection || onStatus
        }

        if (match != null) {
            deque.remove(match)
            match.process.destroyForcibly()
            match.future.cancel(true)
            parent.removeStatusDisplay(editor, match.statusDisplay)
            match.range.dispose()
            if (deque.isEmpty()) {
                parent.unregisterEsc(editor)
            }
        } else {
            val originalEsc = ActionManager.getInstance()
                .getAction(IdeActions.ACTION_EDITOR_ESCAPE)
            originalEsc?.actionPerformed(e)
        }
    }
}
