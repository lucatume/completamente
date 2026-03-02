package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

data class Chunk(
    val text: String,
    val time: Long,
    val filename: String,
    val estimatedTokens: Int = 0
)

@Service(Service.Level.PROJECT)
class ChunksRingBuffer(private val project: Project) {
    private val ringChunks = mutableListOf<Chunk>()
    private val ringQueued = mutableListOf<Chunk>()

    fun getRingChunks(): MutableList<Chunk> {
        return ringChunks
    }

    fun getRingQueued(): MutableList<Chunk> {
        return ringQueued
    }
}
