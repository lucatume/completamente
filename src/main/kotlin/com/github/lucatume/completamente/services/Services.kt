package com.github.lucatume.completamente.services

import io.ktor.client.HttpClient

data class Services(
    val settings: Settings,
    val cache: SuggestionCache,
    val chunksRingBuffer: ChunksRingBuffer,
    val backgroundJobs: BackgroundJobs,
    val httpClient: HttpClient
)
