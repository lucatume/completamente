package com.github.lucatume.completamente.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

const val DEFAULT_ORDER89_CLI_COMMAND: String =
    "pi --tools read,grep,find,ls @\"%%prompt_file%%\" -p \"Execute the instructions in the files\""

const val DEFAULT_WALKTHROUGH_CLI_COMMAND: String =
    "pi --tools read,grep,find,ls @\"%%prompt_file%%\" -p \"Read the instructions in the file and produce the walkthrough\""

@Service(Service.Level.APP)
@State(
    name = "com.github.lucatume.completamente.services.SettingsState",
    storages = [Storage("completamente.xml")]
)
class SettingsState : PersistentStateComponent<SettingsState> {
    // FIM Completions
    var serverUrl: String = "http://127.0.0.1:8012"
    var contextSize: Int = 32768
    var nPredict: Int = 128
    var autoSuggestions: Boolean = true

    // Ring Buffer (Extra Context)
    var ringNChunks: Int = 16
    var ringChunkSize: Int = 64
    var maxQueuedChunks: Int = 16

    // Order 89
    var order89CliCommand: String = DEFAULT_ORDER89_CLI_COMMAND

    // Walkthrough
    var walkthroughCliCommand: String = DEFAULT_WALKTHROUGH_CLI_COMMAND

    // Debug
    var debugLogging: Boolean = false

    override fun getState(): SettingsState = this

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun toSettings(): Settings = Settings(
        serverUrl = serverUrl,
        contextSize = contextSize,
        nPredict = nPredict,
        autoSuggestions = autoSuggestions,
        ringNChunks = ringNChunks,
        ringChunkSize = ringChunkSize,
        maxQueuedChunks = maxQueuedChunks,
        order89CliCommand = order89CliCommand,
        walkthroughCliCommand = walkthroughCliCommand,
        debugLogging = debugLogging
    )

    companion object {
        fun getInstance(): SettingsState =
            ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}
