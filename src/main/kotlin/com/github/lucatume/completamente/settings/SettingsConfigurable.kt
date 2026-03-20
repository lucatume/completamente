package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

class SettingsConfigurable : Configurable {
    private var dialogPanel: DialogPanel? = null
    private val state = SettingsState.getInstance()
    private var toolUsageCombo: JComboBox<String>? = null

    // Current field values — kept in sync with the UI via bind*() on apply/reset
    private var serverUrl = ""
    private var contextSize = ""
    private var nPredict = ""
    private var autoSuggestions = false
    private var ringNChunks = ""
    private var ringChunkSize = ""
    private var maxQueuedChunks = ""
    private var order89ServerUrl = ""
    private var order89Temperature = ""
    private var order89TopP = ""
    private var order89TopK = ""
    private var order89RepeatPenalty = ""
    private var order89NPredict = ""
    private var order89MaxToolRounds = ""

    override fun getDisplayName(): String = "completamente"

    override fun createComponent(): JComponent {
        loadFromState()

        dialogPanel = panel {
            group("FIM Completions") {
                row("Server URL:") {
                    textField()
                        .bindText(::serverUrl)
                        .comment("llama.cpp server endpoint")
                }
                row("Context size:") {
                    textField()
                        .bindText(::contextSize)
                        .comment("Context window size in tokens")
                }
                row("Max predicted tokens:") {
                    textField()
                        .bindText(::nPredict)
                        .comment("Maximum number of tokens to predict")
                }
                row {
                    checkBox("Enable auto-suggestions")
                        .bindSelected(::autoSuggestions)
                }
            }
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
                row("Server URL:") {
                    textField()
                        .bindText(::order89ServerUrl)
                        .comment("llama.cpp server endpoint for Order 89")
                }
                row("Temperature:") {
                    textField()
                        .bindText(::order89Temperature)
                        .comment("Sampling temperature (0.0–2.0)")
                }
                row("Top-P:") {
                    textField()
                        .bindText(::order89TopP)
                        .comment("Nucleus sampling probability (0.0–1.0)")
                }
                row("Top-K:") {
                    textField()
                        .bindText(::order89TopK)
                        .comment("Top-K sampling (0 to disable)")
                }
                row("Repeat penalty:") {
                    textField()
                        .bindText(::order89RepeatPenalty)
                        .comment("Repetition penalty (1.0 = no penalty)")
                }
                row("Max predicted tokens:") {
                    textField()
                        .bindText(::order89NPredict)
                        .comment("Maximum number of tokens to predict")
                }
                row("Tool usage:") {
                    val combo = JComboBox(DefaultComboBoxModel(arrayOf("OFF", "MANUAL", "AUTO")))
                    combo.selectedItem = state.order89ToolUsage
                    toolUsageCombo = combo
                    cell(combo)
                        .comment("OFF = no tools, MANUAL = /tools prefix, AUTO = always")
                }
                row("Max tool rounds:") {
                    textField()
                        .bindText(::order89MaxToolRounds)
                        .comment("Maximum rounds of tool calls before generating code (1-10)")
                }
            }
        }

        return dialogPanel!!
    }

    private fun loadFromState() {
        serverUrl = state.serverUrl
        contextSize = state.contextSize.toString()
        nPredict = state.nPredict.toString()
        autoSuggestions = state.autoSuggestions
        ringNChunks = state.ringNChunks.toString()
        ringChunkSize = state.ringChunkSize.toString()
        maxQueuedChunks = state.maxQueuedChunks.toString()
        order89ServerUrl = state.order89ServerUrl
        order89Temperature = state.order89Temperature.toString()
        order89TopP = state.order89TopP.toString()
        order89TopK = state.order89TopK.toString()
        order89RepeatPenalty = state.order89RepeatPenalty.toString()
        order89NPredict = state.order89NPredict.toString()
        order89MaxToolRounds = state.order89MaxToolRounds.toString()
        toolUsageCombo?.selectedItem = state.order89ToolUsage
    }

    override fun isModified(): Boolean {
        if (dialogPanel?.isModified() == true) return true
        val comboValue = toolUsageCombo?.selectedItem as? String ?: "OFF"
        return comboValue != state.order89ToolUsage
    }

    override fun apply() {
        dialogPanel?.apply()
        applyToState()
    }

    private fun applyToState() {
        val defaults = SettingsState()
        state.serverUrl = serverUrl
        state.contextSize = contextSize.toIntOrNull() ?: defaults.contextSize
        state.nPredict = nPredict.toIntOrNull() ?: defaults.nPredict
        state.autoSuggestions = autoSuggestions
        state.ringNChunks = ringNChunks.toIntOrNull() ?: defaults.ringNChunks
        state.ringChunkSize = ringChunkSize.toIntOrNull() ?: defaults.ringChunkSize
        state.maxQueuedChunks = maxQueuedChunks.toIntOrNull() ?: defaults.maxQueuedChunks
        state.order89ServerUrl = order89ServerUrl
        state.order89Temperature = order89Temperature.toDoubleOrNull() ?: defaults.order89Temperature
        state.order89TopP = order89TopP.toDoubleOrNull() ?: defaults.order89TopP
        state.order89TopK = order89TopK.toIntOrNull() ?: defaults.order89TopK
        state.order89RepeatPenalty = order89RepeatPenalty.toDoubleOrNull() ?: defaults.order89RepeatPenalty
        state.order89NPredict = order89NPredict.toIntOrNull() ?: defaults.order89NPredict
        state.order89ToolUsage = toolUsageCombo?.selectedItem as? String ?: defaults.order89ToolUsage
        state.order89MaxToolRounds = order89MaxToolRounds.toIntOrNull() ?: defaults.order89MaxToolRounds
    }

    override fun reset() {
        loadFromState()
        dialogPanel?.reset()
    }
}
