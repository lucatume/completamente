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
    var order89ServerUrl: String = "http://127.0.0.1:8017"
    var order89Temperature: Double = 0.7
    var order89TopP: Double = 0.8
    var order89TopK: Int = 20
    var order89RepeatPenalty: Double = 1.05
    var order89NPredict: Int = 1024

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
        order89ServerUrl = order89ServerUrl,
        order89Temperature = order89Temperature,
        order89TopP = order89TopP,
        order89TopK = order89TopK,
        order89RepeatPenalty = order89RepeatPenalty,
        order89NPredict = order89NPredict
    )

    companion object {
        fun getInstance(): SettingsState =
            ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}
