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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
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
     * Spec values: `"(range adjusted)"` after clamp, `"(range no longer valid)"` after the
     * RangeMarker loses validity.
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
        val anchor = computeAnchor() ?: return
        popup.show(anchor)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (!popup.isDisposed) popup.cancel()
    }

    private fun computeAnchor(): RelativePoint? {
        // Convert the range start to a screen point above the visual line. Clamp to the visible
        // area so the popup stays attached to the editor even if the range is scrolled off.
        return try {
            val visualPos = editor.offsetToVisualPosition(rangeStartOffset.coerceAtMost(editor.document.textLength))
            val xy = editor.visualPositionToXY(visualPos)
            // Offset above the line so the popup doesn't cover the highlighted code.
            val point = java.awt.Point(xy.x, maxOf(0, xy.y - 8))
            RelativePoint(editor.contentComponent, point)
        } catch (_: Throwable) {
            null
        }
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

        // Narration body — hidden when null.
        if (state.narration != null) {
            val area = JTextArea(state.narration).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                this.font = font
                this.background = bg
                this.foreground = fg
                border = BorderFactory.createEmptyBorder()
            }
            val scroll = JScrollPane(area).apply {
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
}
