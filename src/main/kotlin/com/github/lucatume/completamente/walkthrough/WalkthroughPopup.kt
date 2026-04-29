package com.github.lucatume.completamente.walkthrough

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.github.lucatume.completamente.services.DebugLog
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants

/**
 * State passed to the popup so it can render — pure data, decouples the popup from the
 * navigator's mutable internals. Built fresh on each step transition.
 */
data class WalkthroughPopupState(
    val narration: String?,
    val currentIndex: Int,
    val totalReachable: Int,
    val canGoFirst: Boolean,
    val canGoPrev: Boolean,
    val canGoNext: Boolean,
    val canGoLast: Boolean,
    /**
     * Footer status line to display in italics. `null` hides the line.
     * Set to `"(range no longer valid)"` after the RangeMarker loses validity post-edit.
     */
    val footerStatus: String? = null
)

/** Callbacks the popup invokes for user actions. */
data class WalkthroughPopupHandlers(
    val onFirst: () -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onLast: () -> Unit,
    val onClose: () -> Unit
)

/**
 * Builds and shows a non-modal [JBPopup] anchored above an editor range, re-anchoring on
 * scroll/caret events and disposing cleanly via the parent [Disposable].
 *
 * The popup itself is stateless w.r.t. navigation — the action layer disposes and rebuilds
 * the popup on every step transition. This keeps the popup logic simple (no diffing) and
 * matches the spec's "instant transitions, no intermediate frames" rule.
 */
class WalkthroughPopup(
    private val editor: Editor,
    private val rangeStartOffset: Int,
    private val rangeEndOffset: Int,
    private val state: WalkthroughPopupState,
    private val handlers: WalkthroughPopupHandlers,
    private val parentDisposable: Disposable
) {
    private val scheme = EditorColorsManager.getInstance().globalScheme
    private val popup: JBPopup
    private val anchorAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    @Volatile private var disposed = false

    init {
        val content = buildContent()
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, /* preferredFocusedComponent = */ null)
            .setRequestFocus(false)
            .setFocusable(true)
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .setMinSize(JBUI.size(360, 120))
            .createPopup()

        // Esc inside the popup disposes the session. Handled here so popup-focused users have
        // a working keyboard escape; the editor-scoped handler is registered separately by
        // the action.
        val escKey = KeyStroke.getKeyStroke("ESCAPE")
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escKey, "closeWalkthrough")
        content.actionMap.put("closeWalkthrough", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                handlers.onClose()
            }
        })

        // Listeners — auto-removed via parent Disposable. ScrollingModel has no parented
        // overload for VisibleAreaListener so we register a manual cleanup. CaretModel does.
        val visibleListener = VisibleAreaListener { _: VisibleAreaEvent -> scheduleReanchor() }
        editor.scrollingModel.addVisibleAreaListener(visibleListener)
        Disposer.register(parentDisposable, Disposable {
            try {
                editor.scrollingModel.removeVisibleAreaListener(visibleListener)
            } catch (e: IllegalStateException) {
                // editor may already be disposed — best-effort cleanup
                DebugLog.log("WalkthroughPopup ignoring listener-removal on disposed editor: ${e.message}")
            }
        })

        Disposer.register(parentDisposable, Disposable {
            disposed = true
            if (!popup.isDisposed) popup.cancel()
        })
    }

    fun show() {
        if (disposed) return
        // Best-effort hint to AbstractPopup.show() — the platform's layout pass may overwrite
        // `size` during show, so the post-show re-anchor below is the real correctness
        // mechanism. Setting prefSize first reduces the visible jump in the common case.
        popup.content?.preferredSize?.let { popup.size = it }
        val initial = computeAnchor() ?: return
        popup.show(initial)
        // Defensive re-anchor: if Swing rounding or a font-size discrepancy made the realized
        // popup taller than the prefSize hint, recompute. Both `computeAnchor` calls run on the
        // same EDT pump, so contentComponent's screen origin is stable — comparing screen Y is
        // equivalent to comparing local Y.
        val refined = computeAnchor() ?: return
        if (!popup.isDisposed && popup.isVisible && refined.screenPoint != initial.screenPoint) {
            popup.setLocation(refined.screenPoint)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (!popup.isDisposed) popup.cancel()
    }

    private fun computeAnchor(): RelativePoint? {
        // Both `visualPositionToXY` and `scrollingModel.visibleArea` are in editor-content
        // coordinates (rooted at `editor.contentComponent`), the same space `RelativePoint`
        // consumes — so the comparison and arithmetic in `chooseAnchorY` is well-defined.
        return try {
            val docLen = editor.document.textLength
            val startVisual = editor.offsetToVisualPosition(rangeStartOffset.coerceAtMost(docLen))
            val endVisual = editor.offsetToVisualPosition(rangeEndOffset.coerceAtMost(docLen))
            val startXY = editor.visualPositionToXY(startVisual)
            val endXY = editor.visualPositionToXY(endVisual)
            val visible = editor.scrollingModel.visibleArea
            val y = chooseAnchorY(
                rangeStartY = startXY.y,
                rangeEndY = endXY.y,
                lineHeight = editor.lineHeight,
                popupHeight = popupHeightHint(),
                visibleTopY = visible.y,
                gap = JBUI.scale(4)
            )
            RelativePoint(editor.contentComponent, java.awt.Point(startXY.x, y))
        } catch (_: Throwable) {
            null
        }
    }

    /** Best estimate of popup height — `popup.size` once shown, content preferred-size otherwise. */
    private fun popupHeightHint(): Int {
        val current = popup.size
        if (current != null && current.height > 0) return current.height
        return popup.content?.preferredSize?.height ?: FALLBACK_POPUP_HEIGHT
    }

    private fun scheduleReanchor() {
        if (disposed) return
        // Coalesce 60Hz-ish so fast scrolls don't flicker the popup.
        anchorAlarm.cancelAllRequests()
        anchorAlarm.addRequest({
            if (disposed) return@addRequest
            if (popup.isDisposed) return@addRequest
            if (!popup.isVisible) return@addRequest
            val anchor = computeAnchor() ?: return@addRequest
            popup.setLocation(anchor.screenPoint)
        }, 16)
    }

    private fun buildContent(): JComponent {
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val font = Font(Font.MONOSPACED, Font.PLAIN, scheme.editorFontSize)

        val panel = JPanel(BorderLayout(0, 4)).apply {
            background = bg
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }

        // Narration body — hidden when null. Rendered as HTML so the markdown subset and
        // legacy <code>/<b>/<i>/<em>/<strong> tags from older agents render natively.
        if (state.narration != null) {
            val pane = JEditorPane().apply {
                contentType = "text/html"
                isEditable = false
                this.background = bg
                this.foreground = fg
                border = BorderFactory.createEmptyBorder()
                // JEditorPane's HTMLEditorKit ignores the JComponent.font; styling lives in the
                // HTML <body> via CSS. Inject font/colour from the editor scheme so the popup
                // tracks theme changes for free.
                document.putProperty("IgnoreCharsetDirective", true)
                text = wrapWithStyle(NarrationRenderer.toHtml(state.narration), font, fg)
                // JEditorPane parks the caret at end-of-document on `text =`, which makes
                // the enclosing JScrollPane track-to-caret-scroll to the bottom on first
                // show. Pin caret to the top so long narrations open scrolled to the top.
                caretPosition = 0
                addHyperlinkListener { e ->
                    if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                        e.url?.toExternalForm()?.let { com.intellij.ide.BrowserUtil.browse(it) }
                    }
                }
                // JEditorPane reports a wide preferred size when given long single-line HTML;
                // we pin a width so the scroll-pane wraps content within the popup.
                size = Dimension(420, 160)
            }
            val scroll = JScrollPane(pane).apply {
                border = BorderFactory.createEmptyBorder()
                viewport.background = bg
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize = Dimension(420, 160)
            }
            panel.add(scroll, BorderLayout.CENTER)
        }

        // Status footer — italic, hidden when null.
        if (state.footerStatus != null) {
            val footer = JLabel(state.footerStatus).apply {
                this.font = Font(font.family, Font.ITALIC, font.size)
                this.foreground = fg
                this.background = bg
                isOpaque = true
                border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            }
            panel.add(footer, BorderLayout.NORTH)
        }

        // Button row.
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = bg
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }
        row.add(navButton("«", state.canGoFirst, handlers.onFirst))
        row.add(navButton("‹", state.canGoPrev, handlers.onPrev))
        row.add(JLabel("step ${state.currentIndex}/${state.totalReachable}").apply {
            this.font = font
            this.foreground = fg
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        })
        row.add(navButton("›", state.canGoNext, handlers.onNext))
        row.add(navButton("»", state.canGoLast, handlers.onLast))
        row.add(JLabel("   ").apply { foreground = fg; this.font = font })
        row.add(navButton("×", true, handlers.onClose))
        panel.add(row, BorderLayout.SOUTH)

        return panel
    }

    private fun navButton(text: String, enabled: Boolean, onClick: () -> Unit): JButton =
        JButton(text).apply {
            isFocusable = false
            isEnabled = enabled
            margin = JBUI.insets(2, 6)
            addActionListener { onClick() }
        }

    /**
     * Inject font-family/size and foreground colour into the HTML body so the JEditorPane
     * matches the editor scheme. The CSS lives inside the document to avoid mutating a shared
     * `HTMLEditorKit` style sheet.
     */
    private fun wrapWithStyle(html: String, font: Font, fg: java.awt.Color): String {
        val rgb = String.format("#%02x%02x%02x", fg.red, fg.green, fg.blue)
        val css = "body{font-family:'${font.family}';font-size:${font.size}pt;color:$rgb;" +
            "margin:0;word-wrap:break-word;}" +
            "code{font-family:'${font.family}';}" +
            "a{color:$rgb;text-decoration:underline;}"
        // NarrationRenderer.toHtml already wraps in <html><body>; inject a <style> after <html>.
        return html.replaceFirst("<html>", "<html><head><style>$css</style></head>")
    }

    companion object {
        /** Height to assume when the popup hasn't laid out yet and content has no preferred size. */
        private const val FALLBACK_POPUP_HEIGHT = 200

        /**
         * Choose the popup's top-Y so it never covers the highlighted range. Prefers
         * above-the-range (popup bottom sits `gap` px above the start line); falls back to
         * below-the-range (below the *end* line — matters for multi-line ranges) when there
         * isn't enough room above the visible area. All inputs/outputs are in editor-content
         * coordinates (i.e. relative to `editor.contentComponent`, the same space as
         * `editor.visualPositionToXY` and `editor.scrollingModel.visibleArea`).
         *
         * Note: when the resolved `rangeEndOffset` lands exactly at end-of-line K,
         * `offsetToVisualPosition(endOffset)` returns line K+1 col 0, so `rangeEndY` already
         * sits at the start of line K+1 and the below-fallback ends up one line further down
         * than strictly necessary. Harmless overshoot — never underlaps the range.
         */
        internal fun chooseAnchorY(
            rangeStartY: Int,
            rangeEndY: Int,
            lineHeight: Int,
            popupHeight: Int,
            visibleTopY: Int,
            gap: Int
        ): Int {
            val above = rangeStartY - popupHeight - gap
            val below = rangeEndY + lineHeight + gap
            // Above the range is preferred. Flip below only when above would land outside the
            // visible area (range near the top of the viewport).
            return if (above >= visibleTopY) above else below
        }
    }
}
