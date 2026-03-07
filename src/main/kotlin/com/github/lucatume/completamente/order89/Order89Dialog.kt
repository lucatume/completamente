package com.github.lucatume.completamente.order89

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants

class Order89Dialog(parent: Component) : DialogWrapper(parent, true) {

    private val textArea = JBTextArea()

    val promptText: String get() = textArea.text.orEmpty()

    init {
        title = "Order 89"
        init()
    }

    override fun createActions(): Array<Action> = emptyArray()

    override fun createCenterPanel(): JComponent {
        textArea.lineWrap = true
        textArea.wrapStyleWord = true

        val enterKey = KeyStroke.getKeyStroke("ENTER")
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(enterKey, "submitPrompt")
        textArea.actionMap.put("submitPrompt", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                close(OK_EXIT_CODE)
            }
        })

        val scrollPane = JBScrollPane(
            textArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        scrollPane.preferredSize = Dimension(400, 120)

        return scrollPane
    }
}
