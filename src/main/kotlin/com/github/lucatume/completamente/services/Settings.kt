package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.order89.ToolUsageMode

data class Settings(
    val serverUrl: String = "http://127.0.0.1:8012",
    val contextSize: Int = 32768,
    val nPredict: Int = 128,
    val autoSuggestions: Boolean = true,
    val ringNChunks: Int = 16,
    val ringChunkSize: Int = 64,
    val maxQueuedChunks: Int = 16,
    val order89ServerUrl: String = "http://127.0.0.1:8017",
    val order89Temperature: Double = 0.7,
    val order89TopP: Double = 0.8,
    val order89TopK: Int = 20,
    val order89RepeatPenalty: Double = 1.05,
    val order89NPredict: Int = 1024,
    val order89ToolUsage: ToolUsageMode = ToolUsageMode.OFF,
    val order89MaxToolRounds: Int = 3
)
