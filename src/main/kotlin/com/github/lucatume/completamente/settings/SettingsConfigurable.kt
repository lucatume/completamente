package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.services.DEFAULT_ORDER89_CLI_COMMAND_CLAUDE
import com.github.lucatume.completamente.services.DEFAULT_ORDER89_CLI_COMMAND_PI
import com.github.lucatume.completamente.services.DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE
import com.github.lucatume.completamente.services.DEFAULT_WALKTHROUGH_CLI_COMMAND_PI
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextArea

/** Marker key on [JTextArea]/[javax.swing.JButton] client property used by tests to locate components. */
internal const val SETTINGS_COMPONENT_ID: String = "completamente.componentId"

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
    private var walkthroughCliCommand = ""
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
            cliCommandGroup(
                title = "Order 89",
                idPrefix = "order89",
                comment =
                    "The command runs through your shell as <code>\$SHELL -ic &lt;command&gt;</code> — " +
                        "an interactive shell that sources <code>~/.zshrc</code> / <code>~/.bashrc</code>, " +
                        "so PATH set up by <code>nvm</code>, <code>asdf</code>, <code>mise</code>, etc. " +
                        "applies just as it does in a terminal tab. Login-only files " +
                        "(<code>~/.zprofile</code>, <code>~/.bash_profile</code>) are <i>not</i> sourced — " +
                        "if a tool like <code>pi</code> is missing, ensure its PATH addition lives in your " +
                        "interactive rc file. <code>%%prompt_file%%</code> is replaced at run time with the " +
                        "absolute path to the generated prompt file; wrap it in double quotes " +
                        "(<code>\"%%prompt_file%%\"</code>) so paths with spaces pass as a single argument. " +
                        "The working directory is the current project root.",
                initialText = order89CliCommand,
                piTemplate = DEFAULT_ORDER89_CLI_COMMAND_PI,
                claudeTemplate = DEFAULT_ORDER89_CLI_COMMAND_CLAUDE,
                getCurrent = { order89CliCommand },
                setCurrent = { order89CliCommand = it },
            )
            cliCommandGroup(
                title = "Walkthrough",
                idPrefix = "walkthrough",
                comment =
                    "Same execution model as Order 89 — <code>\$SHELL -ic</code>, " +
                        "<code>%%prompt_file%%</code> placeholder, project root as the working directory. " +
                        "Walkthrough is read-only: the agent must not modify any file. The CLI's stdout " +
                        "is parsed as a <code>&lt;Walkthrough&gt;...&lt;/Walkthrough&gt;</code> block of " +
                        "<code>&lt;Step&gt;</code> elements with <code>file=</code> and " +
                        "<code>range=</code> attributes.",
                initialText = walkthroughCliCommand,
                piTemplate = DEFAULT_WALKTHROUGH_CLI_COMMAND_PI,
                claudeTemplate = DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE,
                getCurrent = { walkthroughCliCommand },
                setCurrent = { walkthroughCliCommand = it },
            )
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
        walkthroughCliCommand = state.walkthroughCliCommand
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
        state.walkthroughCliCommand = walkthroughCliCommand.ifBlank { defaults.walkthroughCliCommand }
        state.debugLogging = debugLogging
    }

    override fun reset() {
        loadFromState()
        dialogPanel?.reset()
    }

    private fun Panel.cliCommandGroup(
        title: String,
        idPrefix: String,
        comment: String,
        initialText: String,
        piTemplate: String,
        claudeTemplate: String,
        getCurrent: () -> String,
        setCurrent: (String) -> Unit,
    ) {
        group(title) {
            row { comment(comment) }
            val area = JTextArea(initialText, 3, 0).apply {
                lineWrap = true
                wrapStyleWord = true
                putClientProperty(SETTINGS_COMPONENT_ID, "$idPrefix.textarea")
            }
            val scroll = JBScrollPane(area).apply {
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
            row {
                cell(scroll)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .onApply { setCurrent(area.text) }
                    .onReset { area.text = getCurrent() }
                    .onIsModified { area.text != getCurrent() }
            }.layout(RowLayout.PARENT_GRID)
            row {
                button("Use pi defaults") { area.text = piTemplate }
                    .applyToComponent { putClientProperty(SETTINGS_COMPONENT_ID, "$idPrefix.pi-button") }
                button("Use claude code defaults") { area.text = claudeTemplate }
                    .applyToComponent { putClientProperty(SETTINGS_COMPONENT_ID, "$idPrefix.claude-button") }
            }
        }
    }
}
