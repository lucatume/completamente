package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Extra context entry sent to the llama.cpp server.
 * Translation of the extra_context dict in llama.vim.
 */
@Serializable
data class ExtraContextEntry(
    val text: String,
    val time: Long,
    val filename: String
)

/**
 * Request body for the ring buffer cache-warming request.
 * Translation of the request dict in llama.vim s:ring_update.
 *
 * This is a minimal request with n_predict=0 and minimal timeouts,
 * used only to pre-warm the llama.cpp server's cache with extra context.
 */
@Serializable
data class RingUpdateRequest(
    val input_prefix: String = "",
    val input_suffix: String = "",
    val input_extra: List<ExtraContextEntry>,
    val prompt: String = "",
    val n_predict: Int = 0,
    val temperature: Double = 0.0,
    val stream: Boolean = false,
    val samplers: List<String> = emptyList(),
    val cache_prompt: Boolean = true,
    val t_max_prompt_ms: Int = 1,
    val t_max_predict_ms: Int = 1,
    val response_fields: List<String> = listOf(""),
    val model: String? = null
)

/**
 * Picks a queued chunk, sends it for processing and adds it to ringChunks.
 * Called every [Settings.ringUpdateMs] milliseconds.
 *
 * Translation of llama.vim s:ring_update function.
 *
 * @param settings The settings containing ring and server configuration
 * @param ringChunks The list of processed chunks (extra context for FIM)
 * @param ringQueued The list of queued chunks waiting to be processed
 * @param lastMoveMs Timestamp of the last cursor movement in milliseconds
 * @param httpClient The Ktor HTTP client for sending requests
 */
fun ringUpdate(
    settings: Settings,
    ringChunks: MutableList<Chunk>,
    ringQueued: MutableList<Chunk>,
    lastMoveMs: Long,
    httpClient: HttpClient,
    coroutineScope: CoroutineScope
) {
    // Update only if the cursor hasn't moved for a while (3 seconds)
    // In llama.vim: reltimefloat(reltime(s:t_last_move)) < 3.0
    if ((System.currentTimeMillis() - lastMoveMs) < 3000) {
        return
    }

    if (ringQueued.isEmpty()) {
        return
    }

    // Move the first queued chunk to the ring buffer
    // If ringChunks is at capacity, remove the oldest entry
    if (ringChunks.size == settings.ringNChunks) {
        ringChunks.removeAt(0)
    }

    // Move the chunk from the queue to the ring buffer.
    val chunk = ringQueued.removeAt(0)
    ringChunks.add(chunk)

    // Build extra context from all ring chunks
    val extraContext = ringChunks.map { c ->
        ExtraContextEntry(
            text = c.text,
            time = c.time,
            filename = c.filename
        )
    }

    // Build the cache-warming request
    // No samplers needed here - this is just to pre-warm the server's cache
    val request = RingUpdateRequest(
        input_extra = extraContext,
        model = settings.model.ifEmpty { null }
    )

    val json = Json { encodeDefaults = true }
    val requestJson = json.encodeToString(request)

    // Send asynchronous request with no callbacks (fire and forget)
    // The response is not needed - we just want to warm the cache
    coroutineScope.launch {
        try {
            httpClient.post(settings.endpoint) {
                contentType(ContentType.Application.Json)
                if (settings.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${settings.apiKey}")
                }
                setBody(requestJson)
            }
        } catch (_: Exception) {
            // Silently ignore errors - this is a background cache-warming request
        }
    }
}
