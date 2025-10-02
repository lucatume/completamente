package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings
import com.github.lucatume.completamente.services.SuggestionCache
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Request body for FIM completion sent to the llama.cpp server.
 * Translation of the request dict in llama.vim llama#fim (lines 646-674).
 */
@Serializable
data class FimRequest(
    val slot_id: Int = 0,
    val input_prefix: String,
    val input_suffix: String,
    val input_extra: List<FimExtraContext>,
    val prompt: String,
    val n_predict: Int,
    val stop: List<String>,
    val n_indent: Int,
    val top_k: Int = 40,
    val top_p: Double = 0.90,
    val stream: Boolean = false,
    val samplers: List<String> = listOf("top_k", "top_p", "infill"),
    val cache_prompt: Boolean = true,
    val t_max_prompt_ms: Int,
    val t_max_predict_ms: Int,
    val response_fields: List<String> = listOf(
        "content",
//        "timings/prompt_n",
//        "timings/prompt_ms",
//        "timings/prompt_per_token_ms",
//        "timings/prompt_per_second",
//        "timings/predicted_n",
//        "timings/predicted_ms",
//        "timings/predicted_per_token_ms",
//        "timings/predicted_per_second",
//        "truncated",
//        "tokens_cached"
    ),
    val model: String? = null
)

/**
 * Extra context entry for the FIM request.
 * Translation of the extra_ctx dict items in llama.vim.
 */
@Serializable
data class FimExtraContext(
    val text: String,
    val time: Long,
    val filename: String
)

/**
 * Build extra context list from ring buffer chunks.
 * Translation of the extra_ctx preparation in llama.vim llama#fim (lines 637-644):
 * ```vim
 * let l:extra_ctx = []
 * for l:chunk in s:ring_chunks
 *     call add(l:extra_ctx, {
 *         \ 'text':     l:chunk.str,
 *         \ 'time':     l:chunk.time,
 *         \ 'filename': l:chunk.filename
 *         \ })
 * endfor
 * ```
 *
 * @param ringChunks The list of chunks from the ring buffer.
 * @return List of FimExtraContext entries for the request.
 */
fun buildExtraContext(ringChunks: List<Chunk>): List<FimExtraContext> {
    return ringChunks.map { chunk ->
        FimExtraContext(
            text = chunk.text,
            time = chunk.time,
            filename = chunk.filename
        )
    }
}

/**
 * Build the FIM request body.
 *
 * @param localContext The local context containing prefix, middle, suffix.
 * @param extraContext The extra context from ring buffer chunks.
 * @param settings The settings containing server and prediction configuration.
 * @param tMaxPredictMs The max predict time (may be overridden for speculative requests).
 * @return The FimRequest ready to be serialized and sent.
 */
fun buildFimRequest(
    localContext: LocalContext,
    extraContext: List<FimExtraContext>,
    settings: Settings,
    tMaxPredictMs: Int
): FimRequest {
    return FimRequest(
        input_prefix = localContext.prefix,
        input_suffix = localContext.suffix,
        input_extra = extraContext,
        prompt = localContext.middle,
        n_predict = settings.nPredict,
        stop = settings.stopStrings,
        n_indent = localContext.indent,
        t_max_prompt_ms = settings.tMaxPromptMs,
        t_max_predict_ms = tMaxPredictMs,
        model = settings.model.ifEmpty { null }
    )
}

/**
 * Validate that the response from the server is a valid JSON with content.
 * Translation of the validation in llama.vim s:fim_on_response (lines 756-758):
 * ```vim
 * if l:raw !~# '^\s*{' || l:raw !~# '\v"content"\s*:"'
 *     return
 * endif
 * ```
 *
 * @param raw The raw response string from the server.
 * @return True if the response appears to be valid, false otherwise.
 */
fun isValidFimResponse(raw: String): Boolean {
    if (raw.isEmpty()) {
        return false
    }
    // Check starts with { (ignoring leading whitespace)
    if (!raw.trimStart().startsWith("{")) {
        return false
    }
    // Check contains "content": pattern
    if (!raw.contains(Regex(""""content"\s*:"""))) {
        return false
    }
    return true
}

/**
 * Result of processing a FIM completion for rendering.
 *
 * @property canAccept Whether the suggestion can be accepted (not empty/whitespace only).
 * @property content The processed content lines ready for display.
 *                   The last line includes the line suffix appended.
 * @property posX The x position where the suggestion should be displayed.
 * @property posY The y position (line number) where the suggestion should be displayed.
 * @property lineCur The current line text (possibly modified for whitespace lines).
 */
data class FimRenderResult(
    val canAccept: Boolean,
    val content: List<String>,
    val posX: Int,
    val posY: Int,
    val lineCur: String
)

/**
 * Process a FIM completion response for rendering as a suggestion.
 *
 * Translation of s:fim_render from llama.vim (lines 866-1086), excluding:
 * - The actual rendering logic (virtual text display)
 * - Statistics/info display
 * - Keymap setup
 *
 * This function processes the suggestion and prepares it for display:
 * 1. Splits the suggestion into lines and removes trailing empty lines
 * 2. Handles whitespace-only current lines by trimming leading whitespace from suggestion
 * 3. Removes duplicate suggestions that repeat existing text
 * 4. Appends the line suffix to the last line of content
 * 5. Returns null if the result is only whitespace
 *
 * @param localContext The local context containing current line information.
 * @param suggestion The suggestion text from the server (already extracted from JSON).
 * @param inlineCompletionRequest The request containing document for deduplication checks.
 * @return The processed suggestion ready for rendering, or null if it should be rejected.
 */
fun fimRender(
    localContext: LocalContext,
    suggestion: String,
    inlineCompletionRequest: InlineCompletionRequest
): String? {
    // Split the suggestion into lines, preserving empty entries (like Vim's split with keepempty=1)
    // Translation of lines 891-893 in llama.vim.
    val content = suggestion.split("\n").toMutableList()

    // Remove trailing empty lines
    // Translation of lines 896-898 in llama.vim.
    while (content.isNotEmpty() && content.last().isEmpty()) {
        content.removeAt(content.lastIndex)
    }

    // If no content, add empty string and mark as non-acceptable
    // Translation of lines 918-921 in llama.vim.
    if (content.isEmpty()) {
        return null
    }

    var lineCur = localContext.lineCur
    val lineCurPrefix = localContext.lineCurPrefix
    val lineCurSuffix = localContext.lineCurSuffix

    // If the current line is full of whitespaces, trim matching whitespace from suggestion
    // Translation of lines 929-934 in llama.vim.
    if (lineCur.matches(Regex("^\\s*$"))) {
        val leadingWhitespaceInSuggestion = content[0].takeWhile { it.isWhitespace() }.length
        val lead = minOf(leadingWhitespaceInSuggestion, lineCur.length)

        lineCur = content[0].substring(0, lead)
        content[0] = content[0].substring(lead)
    }

    // Get document info for deduplication checks
    val document = inlineCompletionRequest.document
    val offset = inlineCompletionRequest.startOffset
    val posY = document.getLineNumber(offset)
    val maxY = document.lineCount

    // Helper function to get line text at a given line number
    fun getLineText(lineNum: Int): String {
        if (lineNum < 0 || lineNum >= maxY) return ""
        val start = document.getLineStartOffset(lineNum)
        val end = document.getLineEndOffset(lineNum)
        return document.getText(com.intellij.openapi.util.TextRange(start, end))
    }

    // NOTE: the following is logic for discarding predictions that repeat existing text
    // Translation of lines 939-993 in llama.vim.

    // Truncate if first line is empty
    // Translation of lines 948-950 in llama.vim.
    if (content.size == 1 && content[0].isEmpty()) {
        return null
    }

    // Truncate if first line is empty and next lines repeat existing lines
    // Translation of lines 953-955 in llama.vim.
    if (content.size > 1 && content[0].isEmpty()) {
        val linesAfter = (posY + 1 until posY + content.size)
            .filter { it < maxY }
            .map { getLineText(it) }

        if (content.subList(1, content.size) == linesAfter) {
            return null
        }
    }

    // Truncate if suggestion repeats the suffix
    // Translation of lines 958-960 in llama.vim.
    if (content.size == 1 && content[0] == lineCurSuffix) {
        return null
    }

    // Find the first non-empty line (skip whitespace-only lines)
    // Translation of lines 963-966 in llama.vim.
    var cmpY = posY + 1
    while (cmpY < maxY && getLineText(cmpY).matches(Regex("^\\s*$"))) {
        cmpY++
    }

    // Check if suggestion repeats content starting at cmpY
    // Translation of lines 968-983 in llama.vim.
    if (cmpY < maxY) {
        val cmpLineText = getLineText(cmpY)

        if ((lineCurPrefix + content[0]) == cmpLineText) {
            // Truncate if repeats the next line
            // Translation of lines 970-972 in llama.vim.
            if (content.size == 1) {
                return null
            }

            // Truncate if second line is prefix of cmpY + 1
            // Translation of lines 975-977 in llama.vim.
            if (content.size == 2 && cmpY + 1 < maxY) {
                val nextLineText = getLineText(cmpY + 1)
                if (content[1] == nextLineText.take(content[1].length)) {
                    return null
                }
            }

            // Truncate if middle chunk matches lines [cmpY + 1, cmpY + content.size - 1)
            // Translation of lines 980-982 in llama.vim.
            if (content.size > 2) {
                val middleContent = content.subList(1, content.size).joinToString("\n")
                val docLines = (cmpY + 1 until cmpY + content.size)
                    .filter { it < maxY }
                    .map { getLineText(it) }
                    .joinToString("\n")

                if (middleContent == docLines) {
                    return null
                }
            }
        }
    }

    // Append lineCurSuffix to the last line
    // Translation of line 994 in llama.vim.
    content[content.lastIndex] = content.last() + lineCurSuffix

    // If only whitespaces - reject
    // Translation of lines 997-999 in llama.vim.
    val fullContent = content.joinToString("\n")
    if (fullContent.matches(Regex("^\\s*$"))) {
        return null
    }

    return fullContent
}

/**
 * Main FIM (Fill-In-the-Middle) completion function.
 * Takes local context around the cursor and sends it together with extra context
 * to the llama.cpp server for completion.
 *
 * Translation of llama#fim from llama.vim (lines 543-740).
 *
 * Key differences from the original:
 * - Does not use callbacks - blocks until completion is received
 * - Does not access any globals/services - everything is passed as parameters
 * - Returns the result directly instead of rendering it
 * - The caller is responsible for debouncing/throttling requests
 *
 * @param localContext The local context containing prefix, middle, suffix, and indent.
 * @param extraContext The mutable list of Chunk objects from the ring buffer.
 * @param settings The settings containing server, prediction, and cache configuration.
 * @param cache The suggestion cache for storing and retrieving completions.
 *              The raw JSON response will be cached under all computed context hashes.
 * @param httpClient The Ktor HTTP client for making the request.
 * @param useCache If true, check cache first and skip request if a cached result exists.
 *                 Translation of a:use_cache in llama.vim.
 * @param isSpeculative If true, this is a speculative (follow-up) request.
 *                      Speculative requests use the full tMaxPredictMs timeout.
 *                      Non-speculative (first) requests use a shorter timeout (250ms).
 *                      Translation of checking empty(a:prev) in llama.vim (lines 584-587).
 * @return The raw JSON response from the server, or null if:
 *         - A cached result already exists (when useCache=true)
 *         - The server request failed
 *         - The response was invalid
 */
suspend fun fim(
    localContext: LocalContext,
    extraContext: MutableList<Chunk>,
    settings: Settings,
    cache: SuggestionCache,
    httpClient: HttpClient,
    useCache: Boolean = true,
    isSpeculative: Boolean = false
): String? {
    val prefix = localContext.prefix
    val middle = localContext.middle
    val suffix = localContext.suffix

    // Compute multiple hashes that can be used to find cached completions
    // where the first few lines are missing (e.g., after scrolling down).
    // Translation of lines 593-605 in llama.vim.
    val hashes = computeContextHashes(prefix, middle, suffix)

    // If we already have a cached completion for one of the hashes, don't send a request.
    // Translation of lines 608-614 in llama.vim.
    if (useCache) {
        if (cacheHasAny(cache, hashes)) {
            return null
        }
    }

    // The first request is quick - we will launch a speculative request after it's displayed.
    // Speculative requests (isSpeculative=true) use the full timeout.
    // Translation of lines 584-587 in llama.vim.
    val tMaxPredictMs = if (isSpeculative) {
        settings.tMaxPredictMs
    } else {
        // First request uses shorter timeout (250ms)
        minOf(250, settings.tMaxPredictMs)
    }

    // Build the request
    val fimExtraContext = buildExtraContext(extraContext)
    val request = buildFimRequest(localContext, fimExtraContext, settings, tMaxPredictMs)

    val json = Json { encodeDefaults = true }
    val requestJson = json.encodeToString(request)

    // Send the request to the server
    val raw: String = try {
        val response = httpClient.post(settings.endpoint) {
            contentType(ContentType.Application.Json)
            if (settings.apiKey.isNotEmpty()) {
                header("Authorization", "Bearer ${settings.apiKey}")
            }
            setBody(requestJson)
        }
        response.bodyAsText()
    } catch (_: Exception) {
        return null
    }

    // Validate the response
    // Translation of lines 751-763 in llama.vim s:fim_on_response.
    if (!isValidFimResponse(raw)) {
        return null
    }

    // Cache the response for all hashes
    // Translation of lines 766-768 in llama.vim s:fim_on_response.
    cacheInsertResponse(cache, hashes, raw, settings.maxCacheKeys)

    return extractContentFromResponse(raw)
}
