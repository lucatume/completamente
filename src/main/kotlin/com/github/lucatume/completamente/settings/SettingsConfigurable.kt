package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.services.ServerManager
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants

class SettingsConfigurable : Configurable {
    private var dialogPanel: DialogPanel? = null
    private val state = SettingsState.getInstance()

    // Current field values — kept in sync with the UI via bind*() on apply/reset
    private var serverUrl = ""
    private var ringNChunks = ""
    private var ringChunkSize = ""
    private var maxQueuedChunks = ""
    private var autoSuggestions = true
    private var maxRecentDiffs = ""
    private var serverCommand = ""
    private var order89Command = ""

    // Server management UI components
    private var serverCommandArea: JBTextArea? = null
    private var order89CommandArea: JBTextArea? = null
    private var serverUrlField: JTextField? = null
    private var statusLabel: JBLabel? = null
    private var serverButton: JButton? = null
    private var viewLogsLink: HyperlinkLabel? = null

    override fun getDisplayName(): String = "completamente"

    override fun createComponent(): JComponent {
        loadFromState()

        dialogPanel = panel {
            group("FIM Suggestions") {
                row("Server URL:") {
                    textField()
                        .bindText(::serverUrl)
                        .comment("URL of the llama.cpp completion server")
                        .applyToComponent { serverUrlField = this }
                }
                row {
                    checkBox("Automatic suggestions")
                        .bindSelected(::autoSuggestions)
                        .comment("Automatically show FIM suggestions while typing")
                }
                row("Recent diffs:") {
                    textField()
                        .bindText(::maxRecentDiffs)
                        .comment("Max number of recent edit diffs sent as context (0 to disable)")
                }
            }
            group("Server Management") {
                row("Server command:") {
                    val area = JBTextArea(3, 40)
                    area.lineWrap = true
                    area.wrapStyleWord = true
                    area.text = serverCommand
                    serverCommandArea = area
                    val scrollPane = JBScrollPane(
                        area,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    )
                    cell(scrollPane)
                        .align(AlignX.FILL)
                        .comment("Full command to start the server. Use {{host}} and {{port}} placeholders (replaced from Server URL). Use -hf to load models from HuggingFace.")
                }
                row("Status:") {
                    val label = JBLabel("Checking...")
                    statusLabel = label
                    cell(label)

                    val link = HyperlinkLabel("View Logs")
                    viewLogsLink = link
                    link.isVisible = false
                    link.addHyperlinkListener {
                        val logFile = ServerManager.getInstance().serverLogFile
                        if (logFile != null && logFile.exists()) {
                            RevealFileAction.openFile(logFile)
                        }
                    }
                    cell(link)
                }
                row {
                    val btn = JButton("Start Server")
                    serverButton = btn
                    btn.isVisible = false
                    btn.addActionListener { onServerButtonClick() }
                    cell(btn)
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

        // Check server status asynchronously when panel opens
        refreshServerStatus()

        return dialogPanel!!
    }

    private fun refreshServerStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val manager = ServerManager.getInstance()
            manager.checkServerHealth()
            val serverState = manager.serverState
            val ownership = manager.ownershipState
            ApplicationManager.getApplication().invokeLater({
                updateStatusUI(serverState, ownership)
            }, ModalityState.any())
        }
    }

    private fun updateStatusUI(serverState: ServerManager.ServerState, ownership: ServerManager.OwnershipState) {
        val label = statusLabel ?: return
        val btn = serverButton ?: return

        val binaryConfigured = (serverCommandArea?.text ?: serverCommand).isNotBlank()
        val logFile = ServerManager.getInstance().serverLogFile
        val hasLogFile = logFile != null && logFile.exists()

        val managed = ownership == ServerManager.OwnershipState.MANAGED

        when (serverState) {
            ServerManager.ServerState.RUNNING -> {
                val suffix = if (managed) " (managed)" else " (external)"
                label.text = "\u25CF Running$suffix"
                viewLogsLink?.isVisible = hasLogFile
                if (managed) {
                    btn.text = "Stop Server"
                    btn.isVisible = true
                } else {
                    btn.isVisible = false
                }
            }
            ServerManager.ServerState.STOPPED, ServerManager.ServerState.UNKNOWN -> {
                viewLogsLink?.isVisible = false
                if (binaryConfigured) {
                    label.text = "\u25CB Not running"
                    btn.text = "Start Server"
                    btn.isVisible = true
                } else {
                    label.text = "\u25CB Not running (binary not configured)"
                    btn.isVisible = false
                }
            }
            ServerManager.ServerState.STARTING -> {
                label.text = "\u25CB Starting..."
                viewLogsLink?.isVisible = true
                btn.isVisible = false
            }
            ServerManager.ServerState.ERROR -> {
                label.text = "\u25CF Error starting server."
                viewLogsLink?.isVisible = hasLogFile
                btn.text = "Retry"
                btn.isVisible = binaryConfigured
            }
        }

        // When server is managed and running/starting, lock the URL field to match the command
        val lockUrl = managed && (serverState == ServerManager.ServerState.RUNNING || serverState == ServerManager.ServerState.STARTING)
        serverUrlField?.isEditable = !lockUrl
        if (lockUrl) {
            val (host, port) = ServerManager.extractHostPort(state.serverUrl)
            val displayHost = if (host == "127.0.0.1") "localhost" else host
            val url = "http://$displayHost:$port"
            serverUrlField?.text = url
            serverUrl = url
        }
    }

    private fun onServerButtonClick() {
        val manager = ServerManager.getInstance()
        val btn = serverButton ?: return
        val label = statusLabel ?: return

        btn.isEnabled = false

        if (manager.serverState == ServerManager.ServerState.RUNNING
            && manager.ownershipState == ServerManager.OwnershipState.MANAGED
        ) {
            // Stop
            ApplicationManager.getApplication().executeOnPooledThread {
                manager.stopServer()
                ApplicationManager.getApplication().invokeLater({
                    btn.isEnabled = true
                    refreshServerStatus()
                }, ModalityState.any())
            }
        } else {
            // Start — apply settings first so the manager picks up current values
            dialogPanel?.apply()
            serverCommand = serverCommandArea?.text ?: ""
            applyToState()

            label.text = "\u25CB Starting..."
            viewLogsLink?.isVisible = true
            serverUrlField?.isEditable = false

            ApplicationManager.getApplication().executeOnPooledThread {
                manager.startServer()
                val state = manager.serverState
                val ownership = manager.ownershipState
                ApplicationManager.getApplication().invokeLater({
                    btn.isEnabled = true
                    updateStatusUI(state, ownership)
                }, ModalityState.any())
            }
        }
    }

    private fun loadFromState() {
        serverUrl = state.serverUrl
        ringNChunks = state.ringNChunks.toString()
        ringChunkSize = state.ringChunkSize.toString()
        maxQueuedChunks = state.maxQueuedChunks.toString()
        autoSuggestions = state.autoSuggestions
        maxRecentDiffs = state.maxRecentDiffs.toString()
        serverCommand = state.serverCommand
        order89Command = state.order89Command
    }

    override fun isModified(): Boolean {
        val panelModified = dialogPanel?.isModified() ?: false
        val commandModified = (serverCommandArea?.text ?: "") != state.serverCommand
        val order89Modified = (order89CommandArea?.text ?: "") != state.order89Command
        return panelModified || commandModified || order89Modified
    }

    override fun apply() {
        dialogPanel?.apply()
        serverCommand = serverCommandArea?.text ?: ""
        order89Command = order89CommandArea?.text ?: ""
        applyToState()
    }

    private fun applyToState() {
        state.serverUrl = serverUrl
        state.ringNChunks = ringNChunks.toIntOrNull() ?: 16
        state.ringChunkSize = ringChunkSize.toIntOrNull() ?: 64
        state.maxQueuedChunks = maxQueuedChunks.toIntOrNull() ?: 16
        state.autoSuggestions = autoSuggestions
        state.maxRecentDiffs = maxRecentDiffs.toIntOrNull() ?: 10
        state.serverCommand = serverCommand
        state.order89Command = order89Command
    }

    override fun reset() {
        loadFromState()
        serverCommandArea?.text = serverCommand
        order89CommandArea?.text = order89Command
        dialogPanel?.reset()
    }
}
