package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextArea

class SettingsConfigurable : Configurable {
    private var dialogPanel: DialogPanel? = null
    private val state = SettingsState.getInstance()

    // Current field values — kept in sync with the UI via bind*() on apply/reset
    private var serverUrl = ""
    private var contextSize = ""
    private var nPredict = ""
    private var autoSuggestions = false
    private var ringNChunks = ""
    private var ringChunkSize = ""
    private var maxQueuedChunks = ""
    private var order89CliCommand = ""
    private var debugLogging = false

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
                row {
                    comment(
                        "The command runs through your shell as <code>\$SHELL -ic &lt;command&gt;</code> — " +
                            "an interactive shell that sources <code>~/.zshrc</code> / <code>~/.bashrc</code>, " +
                            "so PATH set up by <code>nvm</code>, <code>asdf</code>, <code>mise</code>, etc. " +
                            "applies just as it does in a terminal tab. Login-only files " +
                            "(<code>~/.zprofile</code>, <code>~/.bash_profile</code>) are <i>not</i> sourced — " +
                            "if a tool like <code>pi</code> is missing, ensure its PATH addition lives in your " +
                            "interactive rc file. <code>%%prompt_file%%</code> is replaced at run time with the " +
                            "absolute path to the generated prompt file; wrap it in double quotes " +
                            "(<code>\"%%prompt_file%%\"</code>) so paths with spaces pass as a single argument. " +
                            "The working directory is the current project root."
                    )
                }
                row {
                    val area = JTextArea(order89CliCommand, 3, 0).apply {
                        lineWrap = true
                        wrapStyleWord = true
                    }
                    val scroll = JBScrollPane(area).apply {
                        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                    }
                    cell(scroll)
                        .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                        .resizableColumn()
                        .onApply { order89CliCommand = area.text }
                        .onReset { area.text = order89CliCommand }
                        .onIsModified { area.text != order89CliCommand }
                }.layout(RowLayout.PARENT_GRID)
            }
            group("Debug") {
                row {
                    checkBox("Enable debug logging")
                        .bindSelected(::debugLogging)
                        .comment("Logs pipeline timing to idea.log")
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
        order89CliCommand = state.order89CliCommand
        debugLogging = state.debugLogging
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() == true

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
        state.order89CliCommand = order89CliCommand.ifBlank { defaults.order89CliCommand }
        state.debugLogging = debugLogging
    }

    override fun reset() {
        loadFromState()
        dialogPanel?.reset()
    }
}
