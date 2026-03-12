package com.github.lucatume.completamente.order89

import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder

class Order89Dialog(parentComponent: Component) :
    JDialog(SwingUtilities.getWindowAncestor(parentComponent) as? Frame, true) {

    private val neonPink = darkenAndDesaturate(Color(255, 16, 240), 0.05)
    private val borderColor = darkenAndDesaturate(neonPink, 0.05)
    private val editorScheme = EditorColorsManager.getInstance().globalScheme
    private val editorBg = editorScheme.defaultBackground
    private val editorFontSize = editorScheme.editorFontSize
    private val cyan = darkenAndDesaturate(Color(0, 255, 255), 0.15)

    private val textArea = JTextArea()
    private var submitted = false

    val promptText: String get() = textArea.text.orEmpty()

    init {
        isUndecorated = true
        layout = BorderLayout()

        // Compute size: 5/8 of editor window width, height = 5/8 of that width.
        val editorWindow = SwingUtilities.getWindowAncestor(parentComponent)
        val dialogWidth = (editorWindow?.width ?: 640) * 5 / 8
        val dialogHeight = dialogWidth * 3 / 8

        val titledBorder = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(borderColor, 1),
            "Order 89",
            TitledBorder.CENTER,
            TitledBorder.TOP,
            Font(Font.MONOSPACED, Font.PLAIN, editorFontSize),
            borderColor
        )
        val inner = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        (contentPane as JComponent).border = BorderFactory.createCompoundBorder(titledBorder, inner)
        contentPane.background = editorBg

        textArea.apply {
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, editorFontSize)
            background = editorBg
            foreground = editorScheme.defaultForeground
            caretColor = editorScheme.defaultForeground
            border = BorderFactory.createEmptyBorder()
        }

        val enterKey = KeyStroke.getKeyStroke("ENTER")
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(enterKey, "submitPrompt")
        textArea.actionMap.put("submitPrompt", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                submitted = true
                dispose()
            }
        })

        val escKey = KeyStroke.getKeyStroke("ESCAPE")
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(escKey, "cancelPrompt")
        textArea.actionMap.put("cancelPrompt", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                submitted = false
                dispose()
            }
        })

        val scrollPane = JScrollPane(
            textArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = editorBg
        }

        add(scrollPane, BorderLayout.CENTER)
        preferredSize = Dimension(dialogWidth, dialogHeight)
        pack()
        setLocationRelativeTo(editorWindow)

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                textArea.requestFocusInWindow()
            }
        })
    }

    fun showAndWait(): Boolean {
        isVisible = true // blocks for modal dialog
        return submitted
    }

    companion object {
        private fun darkenAndDesaturate(c: Color, amount: Double): Color {
            val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
            hsb[1] = (hsb[1] * (1.0 - amount)).toFloat().coerceIn(0f, 1f)
            hsb[2] = (hsb[2] * (1.0 - amount)).toFloat().coerceIn(0f, 1f)
            return Color(Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]))
        }
    }
}
