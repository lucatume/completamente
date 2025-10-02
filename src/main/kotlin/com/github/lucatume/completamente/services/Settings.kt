package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
data class Settings(
    val endpoint: String = "http://127.0.0.1:8012/infill",
    val apiKey: String = "",
    val model: String = "",
    val nPrefix: Int = 256,
    val nSuffix: Int = 64,
    val tabstop: Int = 4,
    val nPredict: Int = 128,
    val stopStrings: List<String> = emptyList(),
    val tMaxPromptMs: Int = 500,
    val tMaxPredictMs: Int = 1000,
    val maxCacheKeys: Int = 250,
    val ringUpdateMs: Long = 1000,
    val ringChunkSize: Int = 64,
    val ringScope: Int = 1024,
    val ringNChunks: Int = 16,
    val maxQueuedChunks: Int = 16
)
