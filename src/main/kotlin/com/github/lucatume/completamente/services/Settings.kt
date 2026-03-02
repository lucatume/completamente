package com.github.lucatume.completamente.services

data class Settings(
    val serverUrl: String = "http://localhost:8017",
    val ringNChunks: Int = 16,
    val ringChunkSize: Int = 64,
    val maxQueuedChunks: Int = 16,
    val autoSuggestions: Boolean = true,
    val maxRecentDiffs: Int = 10,
    val serverCommand: String = "llama-server --host {{host}} --port {{port}} -hf sweepai/sweep-next-edit-1.5B --ctx-size 8192 --parallel 1 --cache-prompt --temp 0.0"
)
