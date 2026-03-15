package com.github.lucatume.completamente.services

data class Settings(
    val ringNChunks: Int = 16,
    val ringChunkSize: Int = 64,
    val maxQueuedChunks: Int = 16,
    val order89Command: String = "cat {{prompt_file}} | claude --dangerously-skip-permissions --print --output-format text"
)
