package com.github.lucatume.completamente.completion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Maps to a single entry in the llama.cpp `input_extra` array. */
@Serializable
data class InfillExtraChunk(val filename: String, val text: String)

/** Request body for the llama.cpp `/infill` endpoint. Note: `tMaxPredictMs` defaults to 250 ms, `samplers` includes `"infill"` for FIM-specific sampling, and `responseFields` uses flat `timings/` keys. */
@Serializable
data class InfillRequest(
    @SerialName("id_slot") val idSlot: Int = 0,
    @SerialName("input_prefix") val inputPrefix: String,
    @SerialName("input_suffix") val inputSuffix: String,
    @SerialName("input_extra") val inputExtra: List<InfillExtraChunk> = emptyList(),
    val prompt: String = "",
    @SerialName("n_predict") val nPredict: Int = 128,
    @SerialName("n_indent") val nIndent: Int = 0,
    @SerialName("top_k") val topK: Int = 40,
    @SerialName("top_p") val topP: Double = 0.90,
    val samplers: List<String> = listOf("top_k", "top_p", "infill"),
    val stream: Boolean = false,
    @SerialName("cache_prompt") val cachePrompt: Boolean = true,
    @SerialName("t_max_prompt_ms") val tMaxPromptMs: Int = 500,
    @SerialName("t_max_predict_ms") val tMaxPredictMs: Int = 250,
    @SerialName("response_fields") val responseFields: List<String> = listOf(
        "content", "timings/prompt_n", "timings/predicted_n",
        "timings/predicted_ms", "truncated", "tokens_cached"
    ),
    val temperature: Double = 0.0,
    val stop: List<String> = emptyList()
)

/**
 * Subset of the llama.cpp infill response filtered by `response_fields`.
 *
 * Timing keys use the flat `timings/` prefix format (e.g. `timings/prompt_n`)
 * rather than a nested object. Deserializing a full server response that contains
 * additional keys (e.g. `model`, `index`) requires `ignoreUnknownKeys = true`.
 */
@Serializable
data class InfillResponse(
    val content: String = "",
    @SerialName("timings/prompt_n") val promptN: Int = 0,
    @SerialName("timings/predicted_n") val predictedN: Int = 0,
    @SerialName("timings/predicted_ms") val predictedMs: Double = 0.0,
    val truncated: Boolean = false,
    @SerialName("tokens_cached") val tokensCached: Int = 0
)

/**
 * Build an [InfillRequest] that warms the server prompt cache without generating tokens.
 *
 * The request sets `nPredict = 0`, empty samplers, and minimal timeouts so the server
 * processes the prompt context and caches it, but returns immediately.
 *
 * @param extra Extra file chunks to include in the cache warming context
 * @return An [InfillRequest] configured for cache warming only
 */
fun buildCacheWarmingRequest(extra: List<InfillExtraChunk>): InfillRequest =
    InfillRequest(
        inputPrefix = "",
        inputSuffix = "",
        inputExtra = extra,
        prompt = "",
        nPredict = 0,
        cachePrompt = true,
        tMaxPromptMs = 1,
        tMaxPredictMs = 1,
        samplers = emptyList(),
        // Intentional: `listOf("")` tells the server to return an empty response body.
        // This matches llama.vim behaviour (see ref rhdp/34-output-llama-vim-style-infill.txt lines 302-304).
        responseFields = listOf(""),
        temperature = 0.0
    )
