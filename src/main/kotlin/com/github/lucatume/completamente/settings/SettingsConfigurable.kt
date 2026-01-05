package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class SettingsConfigurable : Configurable {
    private var panel: JComponent? = null
    private val state = SettingsState.getInstance()

    // Store copies for modification detection
    private var savedEndpoint = ""
    private var savedApiKey = ""
    private var savedModel = ""
    private var savedNPrefix = ""
    private var savedNSuffix = ""
    private var savedNPredict = ""
    private var savedMaxLineSuffix = ""
    private var savedTMaxPromptMs = ""
    private var savedTMaxPredictMs = ""
    private var savedRingNChunks = ""
    private var savedRingChunkSize = ""
    private var savedRingScope = ""
    private var savedRingUpdateMs = ""
    private var savedMaxQueuedChunks = ""
    private var savedMaxCacheKeys = ""
    private var savedTabstop = ""
    private var savedStopStrings = ""

    // Current field values
    private var endpoint = ""
    private var apiKey = ""
    private var model = ""
    private var nPrefix = ""
    private var nSuffix = ""
    private var nPredict = ""
    private var maxLineSuffix = ""
    private var tMaxPromptMs = ""
    private var tMaxPredictMs = ""
    private var ringNChunks = ""
    private var ringChunkSize = ""
    private var ringScope = ""
    private var ringUpdateMs = ""
    private var maxQueuedChunks = ""
    private var maxCacheKeys = ""
    private var tabstop = ""
    private var stopStrings = ""

    override fun getDisplayName(): String = "completamente"

    override fun createComponent(): JComponent {
        loadFromState()
        saveCurrent()

        panel = panel {
            group("Server Connection") {
                row("Endpoint:") {
                    textField()
                        .bindText(::endpoint)
                        .comment("llama.cpp server endpoint")
                }
                row("API Key:") {
                    passwordField()
                        .bindText(::apiKey)
                        .comment("llama.cpp server API key (optional)")
                }
                row("Model:") {
                    textField()
                        .bindText(::model)
                        .comment("Model name when multiple models are loaded (optional)")
                }
            }

            group("Context Configuration") {
                row("Prefix lines:") {
                    textField()
                        .bindText(::nPrefix)
                        .comment("Number of lines before cursor to include in local prefix")
                }
                row("Suffix lines:") {
                    textField()
                        .bindText(::nSuffix)
                        .comment("Number of lines after cursor to include in local suffix")
                }
                row("Max tokens to predict:") {
                    textField()
                        .bindText(::nPredict)
                        .comment("Maximum number of tokens to predict")
                }
                row("Max line suffix:") {
                    textField()
                        .bindText(::maxLineSuffix)
                        .comment("Do not auto-trigger if more than this many characters to the right of cursor")
                }
            }

            group("Timing") {
                row("Max prompt time (ms):") {
                    textField()
                        .bindText(::tMaxPromptMs)
                        .comment("Maximum allotted time for prompt processing")
                }
                row("Max predict time (ms):") {
                    textField()
                        .bindText(::tMaxPredictMs)
                        .comment("Maximum allotted time for prediction")
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
                row("Scope (lines):") {
                    textField()
                        .bindText(::ringScope)
                        .comment("Range around cursor for gathering chunks after FIM")
                }
                row("Update interval (ms):") {
                    textField()
                        .bindText(::ringUpdateMs)
                        .comment("How often to process queued chunks")
                }
                row("Max queued chunks:") {
                    textField()
                        .bindText(::maxQueuedChunks)
                        .comment("Maximum number of chunks in the queue")
                }
            }

            group("Cache") {
                row("Max cache keys:") {
                    textField()
                        .bindText(::maxCacheKeys)
                        .comment("Max number of cached completions to keep")
                }
            }

            group("Miscellaneous") {
                row("Tab stop:") {
                    textField()
                        .bindText(::tabstop)
                        .comment("Tab width for indentation calculation")
                }
                row("Stop strings:") {
                    textField()
                        .bindText(::stopStrings)
                        .comment("Comma-separated strings that immediately stop generation")
                }
            }
        }

        return panel!!
    }

    private fun loadFromState() {
        endpoint = state.endpoint
        apiKey = state.apiKey
        model = state.model
        nPrefix = state.nPrefix.toString()
        nSuffix = state.nSuffix.toString()
        nPredict = state.nPredict.toString()
        maxLineSuffix = state.maxLineSuffix.toString()
        tMaxPromptMs = state.tMaxPromptMs.toString()
        tMaxPredictMs = state.tMaxPredictMs.toString()
        ringNChunks = state.ringNChunks.toString()
        ringChunkSize = state.ringChunkSize.toString()
        ringScope = state.ringScope.toString()
        ringUpdateMs = state.ringUpdateMs.toString()
        maxQueuedChunks = state.maxQueuedChunks.toString()
        maxCacheKeys = state.maxCacheKeys.toString()
        tabstop = state.tabstop.toString()
        stopStrings = state.stopStrings
    }

    private fun saveCurrent() {
        savedEndpoint = endpoint
        savedApiKey = apiKey
        savedModel = model
        savedNPrefix = nPrefix
        savedNSuffix = nSuffix
        savedNPredict = nPredict
        savedMaxLineSuffix = maxLineSuffix
        savedTMaxPromptMs = tMaxPromptMs
        savedTMaxPredictMs = tMaxPredictMs
        savedRingNChunks = ringNChunks
        savedRingChunkSize = ringChunkSize
        savedRingScope = ringScope
        savedRingUpdateMs = ringUpdateMs
        savedMaxQueuedChunks = maxQueuedChunks
        savedMaxCacheKeys = maxCacheKeys
        savedTabstop = tabstop
        savedStopStrings = stopStrings
    }

    override fun isModified(): Boolean {
        return endpoint != savedEndpoint ||
                apiKey != savedApiKey ||
                model != savedModel ||
                nPrefix != savedNPrefix ||
                nSuffix != savedNSuffix ||
                nPredict != savedNPredict ||
                maxLineSuffix != savedMaxLineSuffix ||
                tMaxPromptMs != savedTMaxPromptMs ||
                tMaxPredictMs != savedTMaxPredictMs ||
                ringNChunks != savedRingNChunks ||
                ringChunkSize != savedRingChunkSize ||
                ringScope != savedRingScope ||
                ringUpdateMs != savedRingUpdateMs ||
                maxQueuedChunks != savedMaxQueuedChunks ||
                maxCacheKeys != savedMaxCacheKeys ||
                tabstop != savedTabstop ||
                stopStrings != savedStopStrings
    }

    override fun apply() {
        state.endpoint = endpoint
        state.apiKey = apiKey
        state.model = model
        state.nPrefix = nPrefix.toIntOrNull() ?: 256
        state.nSuffix = nSuffix.toIntOrNull() ?: 64
        state.nPredict = nPredict.toIntOrNull() ?: 128
        state.maxLineSuffix = maxLineSuffix.toIntOrNull() ?: 8
        state.tMaxPromptMs = tMaxPromptMs.toIntOrNull() ?: 500
        state.tMaxPredictMs = tMaxPredictMs.toIntOrNull() ?: 1000
        state.ringNChunks = ringNChunks.toIntOrNull() ?: 16
        state.ringChunkSize = ringChunkSize.toIntOrNull() ?: 64
        state.ringScope = ringScope.toIntOrNull() ?: 1024
        state.ringUpdateMs = ringUpdateMs.toLongOrNull() ?: 1000L
        state.maxQueuedChunks = maxQueuedChunks.toIntOrNull() ?: 16
        state.maxCacheKeys = maxCacheKeys.toIntOrNull() ?: 250
        state.tabstop = tabstop.toIntOrNull() ?: 4
        state.stopStrings = stopStrings

        saveCurrent()
    }

    override fun reset() {
        loadFromState()
        saveCurrent()
    }
}
