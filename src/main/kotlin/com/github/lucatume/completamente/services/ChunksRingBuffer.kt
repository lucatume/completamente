package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service
import com.github.lucatume.completamente.completion.ringUpdate
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Chunk(
    val text: String,
    val time: Long,
    val filename: String
)

@Service(Service.Level.PROJECT)
class ChunksRingBuffer(private val project: Project) {
    private val ringChunks = mutableListOf<Chunk>()
    private val ringQueued = mutableListOf<Chunk>()
    private val movementTracker = project.service<CursorMovementTracker>()

    fun start() {
        val settings = SettingsState.getInstance().toSettings()
        val backgroundJobs = project.service<BackgroundJobs>()
        val ringUpdateMs = settings.ringUpdateMs
        val httpClient = project.service<HttpClient>().getHttpClient()

        backgroundJobs.launch{
            while(isActive){
                val lastMoveMs = movementTracker.getLastMoveMs()
                ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, backgroundJobs)
                delay(ringUpdateMs)
            }
        }
    }

    fun getRingChunks(): MutableList<Chunk> {
        return ringChunks
    }

    fun getRingQueued(): MutableList<Chunk> {
        return ringQueued
    }
}
