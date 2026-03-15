package com.github.lucatume.completamente.services

data class Settings(
    val serverUrl: String = "http://127.0.0.1:8012",
    val contextSize: Int = 32768,
    val nPredict: Int = 128,
    val autoSuggestions: Boolean = true,
    val ringNChunks: Int = 16,
    val ringChunkSize: Int = 64,
    val maxQueuedChunks: Int = 16,
    val order89Command: String = "cat {{prompt_file}} | claude --dangerously-skip-permissions --print --output-format text"
)
