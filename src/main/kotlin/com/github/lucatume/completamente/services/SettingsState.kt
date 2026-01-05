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
    // Server Connection
    var endpoint: String = "http://127.0.0.1:8012/infill"
    var apiKey: String = ""
    var model: String = ""

    // Context Configuration
    var nPrefix: Int = 256
    var nSuffix: Int = 64
    var nPredict: Int = 128
    var maxLineSuffix: Int = 8

    // Timing
    var tMaxPromptMs: Int = 500
    var tMaxPredictMs: Int = 1000

    // Ring Buffer (Extra Context)
    var ringNChunks: Int = 16
    var ringChunkSize: Int = 64
    var ringScope: Int = 1024
    var ringUpdateMs: Long = 1000
    var maxQueuedChunks: Int = 16

    // Cache
    var maxCacheKeys: Int = 250

    // Miscellaneous
    var tabstop: Int = 4
    var stopStrings: String = "" // Comma-separated for UI simplicity

    override fun getState(): SettingsState = this

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * Convert to immutable Settings for use throughout the codebase.
     */
    fun toSettings(): Settings = Settings(
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        nPrefix = nPrefix,
        nSuffix = nSuffix,
        tabstop = tabstop,
        nPredict = nPredict,
        stopStrings = if (stopStrings.isBlank()) emptyList() else stopStrings.split(",").map { it.trim() },
        tMaxPromptMs = tMaxPromptMs,
        tMaxPredictMs = tMaxPredictMs,
        maxCacheKeys = maxCacheKeys,
        ringUpdateMs = ringUpdateMs,
        ringChunkSize = ringChunkSize,
        ringScope = ringScope,
        ringNChunks = ringNChunks,
        maxQueuedChunks = maxQueuedChunks,
        maxLineSuffix = maxLineSuffix
    )

    companion object {
        fun getInstance(): SettingsState =
            ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}
