package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

class SettingsConfigurable : Configurable {
    private var dialogPanel: DialogPanel? = null
    private val state = SettingsState.getInstance()

    // Current field values — kept in sync with the UI via bind*() on apply/reset
    private var ringNChunks = ""
    private var ringChunkSize = ""
    private var maxQueuedChunks = ""
    private var order89Command = ""

    // UI components
    private var order89CommandArea: JBTextArea? = null

    override fun getDisplayName(): String = "completamente"

    override fun createComponent(): JComponent {
        loadFromState()

        dialogPanel = panel {
            group("Ring Buffer (Extra Context)") {
                row("Number of chunks:") {
                    textField()
                        .bindText(::ringNChunks)
                        .comment("Max chunks to pass as extra context (0 to disable)")
                }
                row("Chunk size (lines):") {
                    textField()
                        .bindText(::ringChunkSize)
                        .comment("Max size of chunks in number of lines")
                }
                row("Max queued chunks:") {
                    textField()
                        .bindText(::maxQueuedChunks)
                        .comment("Maximum number of chunks in the queue")
                }
            }
            group("Order 89") {
                row("Command template:") {
                    val area = JBTextArea(3, 40)
                    area.lineWrap = true
                    area.wrapStyleWord = true
                    area.text = order89Command
                    order89CommandArea = area
                    val scrollPane = JBScrollPane(
                        area,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    )
                    cell(scrollPane)
                        .align(AlignX.FILL)
                        .comment("Placeholders: {{prompt_file}}.")
                }
                row {
                    val resetBtn = JButton("Reset")
                    resetBtn.addActionListener {
                        order89CommandArea?.text = SettingsState().order89Command
                    }
                    cell(resetBtn)
                }
            }
        }

        return dialogPanel!!
    }

    private fun loadFromState() {
        ringNChunks = state.ringNChunks.toString()
        ringChunkSize = state.ringChunkSize.toString()
        maxQueuedChunks = state.maxQueuedChunks.toString()
        order89Command = state.order89Command
    }

    override fun isModified(): Boolean {
        val panelModified = dialogPanel?.isModified() ?: false
        val order89Modified = (order89CommandArea?.text ?: "") != state.order89Command
        return panelModified || order89Modified
    }

    override fun apply() {
        dialogPanel?.apply()
        order89Command = order89CommandArea?.text ?: ""
        applyToState()
    }

    private fun applyToState() {
        state.ringNChunks = ringNChunks.toIntOrNull() ?: 16
        state.ringChunkSize = ringChunkSize.toIntOrNull() ?: 64
        state.maxQueuedChunks = maxQueuedChunks.toIntOrNull() ?: 16
        state.order89Command = order89Command
    }

    override fun reset() {
        loadFromState()
        order89CommandArea?.text = order89Command
        dialogPanel?.reset()
    }
}
