package com.github.lucatume.completamente.walkthrough

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.github.lucatume.completamente.services.DebugLog
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.geom.Path2D
import javax.swing.JRootPane
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
import javax.swing.SwingUtilities

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
 * Builds and shows a non-modal [JBPopup] anchored above (or below) an editor range, with a
 * triangular wedge pointing at the line of code being narrated. Re-anchors on scroll/caret
 * events and disposes cleanly via the parent [Disposable].
 *
 * Orientation is chosen at first show and frozen for the remainder of the popup's life:
 * popup above + wedge pointing down (preferred), else popup below + wedge pointing up. Freezing
 * means a rescroll that drains the room above will let the popup float off the top of the
 * viewport rather than flip-flopping orientation under the user.
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
    /** True when the popup sits above the line and the wedge points down at it. Frozen at init. */
    private val pointsDown: Boolean = decideInitialOrientation()
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
            // Disable the platform's rectangular border + shadow — the BalloonPanel paints its own
            // rounded body and tail, and a rectangular shadow around it would betray the bounds.
            .setShowBorder(false)
            .setShowShadow(false)
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

        // Hide the popup window while the IDE is in the background so it doesn't float over
        // other applications. We can't use `setCancelOnWindowDeactivation(true)` because that
        // would tear the walkthrough down on every alt-tab; instead we toggle the underlying
        // Swing window's visibility and let the popup itself stay alive across the focus
        // cycle. ApplicationActivationListener fires only on full app-level activation
        // changes, not on intra-IDE focus moves (e.g. switching tool windows), so the popup
        // stays put while the user is in the IDE.
        val app = ApplicationManager.getApplication()
        val connection = app.messageBus.connect(parentDisposable)
        connection.subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) {
                setPopupWindowVisible(true)
            }

            override fun applicationDeactivated(ideFrame: IdeFrame) {
                setPopupWindowVisible(false)
            }
        })
        // Cover the cold-start case: if the popup is built while the IDE is already in the
        // background (rare, but possible during slow agent runs), hide immediately on first
        // show — the listener above only fires on transitions.
        if (!app.isActive) {
            // Defer until after `show()` has parented the content into a window.
            SwingUtilities.invokeLater { if (!disposed) setPopupWindowVisible(false) }
        }

        Disposer.register(parentDisposable, Disposable {
            disposed = true
            if (!popup.isDisposed) popup.cancel()
        })
    }

    /**
     * `setShowShadow(false)` on the popup builder removes the platform's drawn shadow, but on
     * macOS the heavy-weight Window the popup parents into still casts a native shadow around
     * its rectangular bounds — visible as a halo around the wedge. Setting `Window.shadow=false`
     * on the rootPane disables that native shadow. No-op on Linux/Windows where the property
     * isn't honoured.
     */
    private fun suppressNativeWindowShadow() {
        if (disposed) return
        if (popup.isDisposed) return
        val content = popup.content ?: return
        val rootPane: JRootPane = SwingUtilities.getRootPane(content) ?: return
        rootPane.putClientProperty("Window.shadow", java.lang.Boolean.FALSE)
    }

    private fun setPopupWindowVisible(visible: Boolean) {
        if (disposed) return
        if (popup.isDisposed) return
        val content = popup.content ?: return
        val window = SwingUtilities.getWindowAncestor(content) ?: return
        if (window.isVisible != visible) {
            window.isVisible = visible
        }
    }

    fun show() {
        if (disposed) return
        // Best-effort hint to AbstractPopup.show() — the platform's layout pass may overwrite
        // `size` during show, so the post-show re-anchor below is the real correctness
        // mechanism. Setting prefSize first reduces the visible jump in the common case.
        popup.content?.preferredSize?.let { popup.size = it }
        val initial = computeAnchor() ?: return
        popup.show(initial)
        suppressNativeWindowShadow()
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

    private fun decideInitialOrientation(): Boolean {
        // Use a popup-height estimate before we've measured the real content. Most narrations
        // come out ~200px tall (160 scroll body + ~30 button row + paddings + tail), so this is
        // accurate enough for the above/below decision in the common case. Worst case: the
        // estimate is off by tens of pixels and we pick the wrong side; the popup still works,
        // just oriented suboptimally for that one step.
        return try {
            val docLen = editor.document.textLength
            val startVisual = editor.offsetToVisualPosition(rangeStartOffset.coerceAtMost(docLen))
            val endVisual = editor.offsetToVisualPosition(rangeEndOffset.coerceAtMost(docLen))
            val startXY = editor.visualPositionToXY(startVisual)
            val endXY = editor.visualPositionToXY(endVisual)
            val visible = editor.scrollingModel.visibleArea
            choosePlacement(
                rangeStartY = startXY.y,
                rangeEndY = endXY.y,
                lineHeight = editor.lineHeight,
                popupHeight = ESTIMATED_POPUP_HEIGHT,
                visibleTopY = visible.y,
                gap = JBUI.scale(POPUP_GAP_DP)
            ).pointsDown
        } catch (_: Throwable) {
            true
        }
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
            val gap = JBUI.scale(POPUP_GAP_DP)
            val popupH = popupHeightHint()
            // Frozen orientation: do NOT re-decide here. If the user has scrolled away from
            // where there was room above, the popup floats off-screen rather than flipping
            // mid-session — the wedge keeps pointing the same direction at the line.
            val y = if (pointsDown) startXY.y - popupH - gap
                    else endXY.y + editor.lineHeight + gap
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
            // popup.isVisible stays true even when we've toggled the underlying Window off
            // for app deactivation. Skip the reanchor in that case — repositioning a hidden
            // window is wasted work and has caused multi-monitor affinity quirks on some
            // JDK+macOS combinations historically.
            val window = SwingUtilities.getWindowAncestor(popup.content)
            if (window == null || !window.isVisible) return@addRequest
            val anchor = computeAnchor() ?: return@addRequest
            popup.setLocation(anchor.screenPoint)
        }, 16)
    }

    private fun buildContent(): JComponent {
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        // Accent border — saturated blue chosen to read clearly against editor backgrounds in
        // both light and dark themes. Hard-coded rather than pulled from the editor scheme so
        // every theme gets the same "this is the walkthrough" highlight.
        val borderColor = JBColor(Color(0x3592C4), Color(0x4FA3D9))
        val font = Font(Font.MONOSPACED, Font.PLAIN, scheme.editorFontSize)

        val panel = BalloonPanel(pointsDown = pointsDown, bodyBg = bg, borderColor = borderColor).apply {
            background = bg
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

    /**
     * Custom-painted root panel for the popup — a rounded-rectangle body with a triangular
     * wedge ("tail") on the side that abuts the line of code. Children are laid out with
     * [BorderLayout]; an empty border reserves space for the tail on the appropriate side so
     * children don't overlap it.
     *
     * Painting traces a single combined outline (body + tail) so the border stroke is seamless
     * across the join.
     */
    private class BalloonPanel(
        private val pointsDown: Boolean,
        private val bodyBg: Color,
        private val borderColor: Color
    ) : JPanel(BorderLayout(0, 4)) {

        init {
            // Non-opaque so paintComponent can render the rounded shape without a square bg
            // showing through behind it.
            isOpaque = false
            val tailH = JBUI.scale(WEDGE_HEIGHT_DP)
            val padH = JBUI.scale(BODY_PADDING_DP)
            val padV = JBUI.scale(BODY_PADDING_DP)
            val strokeInset = JBUI.scale(BORDER_STROKE_DP)
            // Reserve room for the tail on the side it sticks out from, plus stroke width on
            // the body sides so the bright border isn't clipped by the panel bounds. The tail
            // height is outside the body's rounded rect — children must not extend into that
            // strip.
            border = BorderFactory.createEmptyBorder(
                padV + (if (!pointsDown) tailH else strokeInset),
                padH + strokeInset,
                padV + (if (pointsDown) tailH else strokeInset),
                padH + strokeInset
            )
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val tailH = JBUI.scale(WEDGE_HEIGHT_DP).toFloat()
                val tailW = JBUI.scale(WEDGE_WIDTH_DP).toFloat()
                val tailInsetX = JBUI.scale(WEDGE_INSET_X_DP).toFloat()
                val cr = JBUI.scale(BALLOON_CORNER_RADIUS_DP).toFloat()
                // Inset the outline by half the stroke width so a centered stroke isn't clipped
                // by the panel bounds. Without this, a 2 px stroke renders as ~1 px on the outer
                // edges (the outer half is painted into the panel's clip and discarded).
                val strokeHalf = JBUI.scale(BORDER_STROKE_DP) / 2f

                val left = strokeHalf
                val right = (width - 1).toFloat() - strokeHalf
                val bodyTop = if (pointsDown) strokeHalf else tailH
                val bodyBottom = (height - 1).toFloat() - if (pointsDown) tailH else strokeHalf
                val tipBottomY = (height - 1).toFloat() - strokeHalf
                val tipTopY = strokeHalf
                val tailLeft = tailInsetX
                val tailRight = tailInsetX + tailW
                val tailMid = tailInsetX + tailW / 2f

                // Trace the outline clockwise starting at top-left after the corner.
                val path = Path2D.Float()
                path.moveTo(left + cr, bodyTop)

                if (!pointsDown) {
                    // Wedge pointing UP — drawn on the top edge between corners.
                    path.lineTo(tailLeft, bodyTop)
                    path.lineTo(tailMid, tipTopY)
                    path.lineTo(tailRight, bodyTop)
                }

                // Top edge → top-right corner
                path.lineTo(right - cr, bodyTop)
                path.quadTo(right, bodyTop, right, bodyTop + cr)
                // Right edge
                path.lineTo(right, bodyBottom - cr)
                path.quadTo(right, bodyBottom, right - cr, bodyBottom)

                if (pointsDown) {
                    // Wedge pointing DOWN — drawn on the bottom edge between corners.
                    path.lineTo(tailRight, bodyBottom)
                    path.lineTo(tailMid, tipBottomY)
                    path.lineTo(tailLeft, bodyBottom)
                }

                // Bottom edge → bottom-left corner
                path.lineTo(left + cr, bodyBottom)
                path.quadTo(left, bodyBottom, left, bodyBottom - cr)
                // Left edge → top-left corner
                path.lineTo(left, bodyTop + cr)
                path.quadTo(left, bodyTop, left + cr, bodyTop)
                path.closePath()

                g2.color = bodyBg
                g2.fill(path)
                g2.color = borderColor
                g2.stroke = BasicStroke(JBUI.scale(BORDER_STROKE_DP).toFloat())
                g2.draw(path)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** Height to assume when the popup hasn't laid out yet and content has no preferred size. */
        private const val FALLBACK_POPUP_HEIGHT = 200

        /** Pre-measurement estimate used when deciding orientation before the panel exists. */
        private const val ESTIMATED_POPUP_HEIGHT = 220

        /** Gap, in DP, between the wedge tip and the line edge it points at. */
        private const val POPUP_GAP_DP = 2

        /** Height (DP) of the triangular wedge. */
        private const val WEDGE_HEIGHT_DP = 8

        /** Width (DP) of the triangular wedge. */
        private const val WEDGE_WIDTH_DP = 14

        /** Distance (DP) from the panel's left edge to the wedge's left edge. */
        private const val WEDGE_INSET_X_DP = 12

        /** Corner radius (DP) of the rounded body. */
        private const val BALLOON_CORNER_RADIUS_DP = 8

        /** Internal padding (DP) between the body's outer edge and its child content. */
        private const val BODY_PADDING_DP = 8

        /** Stroke width (DP) of the accent border around the body + wedge. */
        private const val BORDER_STROKE_DP = 2

        /** Geometry decision: top-Y for the popup, plus whether the wedge points down (above-line) or up (below-line). */
        data class AnchorPlacement(val y: Int, val pointsDown: Boolean)

        /**
         * Choose the popup's top-Y and orientation. Prefers above-the-range (popup bottom and
         * wedge tip sit `gap` px above the start line); falls back to below-the-range (wedge
         * tip sits `gap` px below the end line — multi-line aware) when there isn't enough
         * room above the visible area. All inputs/outputs are in editor-content coordinates
         * (i.e. relative to `editor.contentComponent`, the same space as
         * `editor.visualPositionToXY` and `editor.scrollingModel.visibleArea`).
         *
         * Note: when the resolved `rangeEndOffset` lands exactly at end-of-line K,
         * `offsetToVisualPosition(endOffset)` returns line K+1 col 0, so `rangeEndY` already
         * sits at the start of line K+1 and the below-fallback ends up one line further down
         * than strictly necessary. Harmless overshoot — never underlaps the range.
         */
        internal fun choosePlacement(
            rangeStartY: Int,
            rangeEndY: Int,
            lineHeight: Int,
            popupHeight: Int,
            visibleTopY: Int,
            gap: Int
        ): AnchorPlacement {
            val above = rangeStartY - popupHeight - gap
            val below = rangeEndY + lineHeight + gap
            return if (above >= visibleTopY) AnchorPlacement(above, pointsDown = true)
                   else AnchorPlacement(below, pointsDown = false)
        }

        /**
         * Back-compat shim returning just the Y coordinate; preserves the original
         * pre-wedge contract for callers that don't care about orientation.
         */
        internal fun chooseAnchorY(
            rangeStartY: Int,
            rangeEndY: Int,
            lineHeight: Int,
            popupHeight: Int,
            visibleTopY: Int,
            gap: Int
        ): Int = choosePlacement(
            rangeStartY = rangeStartY,
            rangeEndY = rangeEndY,
            lineHeight = lineHeight,
            popupHeight = popupHeight,
            visibleTopY = visibleTopY,
            gap = gap
        ).y
    }
}
