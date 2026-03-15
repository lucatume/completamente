package com.github.lucatume.completamente.completion

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for llama.cpp /infill endpoint.
 * Uses java.net.http.HttpClient for non-blocking requests.
 */
class InfillClient(private val baseUrl: String) {

    companion object {
        private val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val httpClient: HttpClient get() = sharedHttpClient

    /**
     * Sends a completion request to /infill and returns the response.
     * @throws InfillClientException on network errors, timeouts, or invalid responses.
     */
    fun sendCompletion(request: InfillRequest): InfillResponse {
        val body = json.encodeToString(request)
        val timeoutMs = request.tMaxPredictMs.toLong() + request.tMaxPromptMs.toLong() + 2000L
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/infill"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMillis(timeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                throw InfillClientException("Server returned status ${response.statusCode()}: ${response.body()}")
            }
            return json.decodeFromString<InfillResponse>(response.body())
        } catch (e: InfillClientException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InfillClientException("Request interrupted", e)
        } catch (e: Exception) {
            throw InfillClientException("Failed to send completion request: ${e.message}", e)
        }
    }

    /**
     * Sends a cache-warming request (n_predict=0) to pre-load input_extra into the KV cache.
     * Fire-and-forget -- errors are silently ignored.
     */
    fun sendCacheWarming(extra: List<InfillExtraChunk>) {
        try {
            val request = buildCacheWarmingRequest(extra)
            val body = json.encodeToString(request)
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/infill"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .exceptionally { null }
        } catch (_: Exception) {
            // Fire and forget -- silently ignore all errors.
        }
    }

    /**
     * Checks if the server is reachable by calling GET /health.
     * Returns true if the server responds with 200 OK within 2 seconds.
     */
    fun isServerReachable(): Boolean {
        return try {
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (_: Exception) {
            false
        }
    }
}

class InfillClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
