package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.*

@Service(Service.Level.PROJECT)
class HttpClient {
    private val httpClient = HttpClient(CIO)

    fun getHttpClient(): HttpClient {
        return httpClient
    }
}
