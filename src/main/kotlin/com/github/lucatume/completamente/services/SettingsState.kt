package com.github.lucatume.completamente.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "com.github.lucatume.completamente.services.SettingsState",
    storages = [Storage("completamente.xml")]
)
class SettingsState : PersistentStateComponent<SettingsState> {
    // Server
    var serverUrl: String = "http://localhost:8017"

    // Ring Buffer (Extra Context)
    var ringNChunks: Int = 16
    var ringChunkSize: Int = 64
    var maxQueuedChunks: Int = 16

    // FIM Suggestions
    var autoSuggestions: Boolean = true
    var maxRecentDiffs: Int = 10

    // Server Management
    var serverCommand: String = "llama-server --host {{host}} --port {{port}} -hf sweepai/sweep-next-edit-1.5B --ctx-size 8192 --parallel 1 --cache-prompt --temp 0.0"

    var order89Command: String = "printf 'Output ONLY code, no explanations, no markdown fences. Do not include any text before or after the code.\\n\\nReplace the following %s code according to this instruction: %s\\n\\n%s' {{language}} {{prompt}} {{selected_text}} | claude --print --output-format text"

    override fun getState(): SettingsState = this

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun toSettings(): Settings = Settings(
        serverUrl = serverUrl,
        ringNChunks = ringNChunks,
        ringChunkSize = ringChunkSize,
        maxQueuedChunks = maxQueuedChunks,
        autoSuggestions = autoSuggestions,
        maxRecentDiffs = maxRecentDiffs,
        serverCommand = serverCommand,
        order89Command = order89Command
    )

    companion object {
        fun getInstance(): SettingsState =
            ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}
