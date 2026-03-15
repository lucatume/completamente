package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray

class infillTest : BaseCompletionTest() {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // --- InfillRequest serialization with defaults ---

    fun testInfillRequestDefaultsSerialization() {
        val request = InfillRequest(inputPrefix = "prefix", inputSuffix = "suffix")
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(0, (obj["id_slot"] as JsonPrimitive).int)
        assertEquals("prefix", (obj["input_prefix"] as JsonPrimitive).content)
        assertEquals("suffix", (obj["input_suffix"] as JsonPrimitive).content)
        assertEquals(128, (obj["n_predict"] as JsonPrimitive).int)
        assertEquals(0, (obj["n_indent"] as JsonPrimitive).int)
        assertEquals(40, (obj["top_k"] as JsonPrimitive).int)
        assertEquals(0.90, (obj["top_p"] as JsonPrimitive).double, 1e-9)
        assertEquals(true, (obj["cache_prompt"] as JsonPrimitive).boolean)
        assertEquals(500, (obj["t_max_prompt_ms"] as JsonPrimitive).int)
        assertEquals(250, (obj["t_max_predict_ms"] as JsonPrimitive).int)
        assertEquals(0.0, (obj["temperature"] as JsonPrimitive).double, 1e-9)
        assertEquals(false, (obj["stream"] as JsonPrimitive).boolean)
    }

    fun testInfillRequestDefaultSamplersField() {
        val request = InfillRequest(inputPrefix = "", inputSuffix = "")
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        val samplers = obj["samplers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("top_k", "top_p", "infill"), samplers)
    }

    fun testInfillRequestDefaultResponseFieldsField() {
        val request = InfillRequest(inputPrefix = "", inputSuffix = "")
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        val fields = obj["response_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf(
                "content", "timings/prompt_n", "timings/predicted_n",
                "timings/predicted_ms", "truncated", "tokens_cached"
            ),
            fields
        )
    }

    // --- InfillRequest serialization with custom values ---

    fun testInfillRequestCustomValuesSerialization() {
        val extra = listOf(InfillExtraChunk(filename = "foo.kt", text = "some code"))
        val request = InfillRequest(
            idSlot = 3,
            inputPrefix = "before cursor",
            inputSuffix = "after cursor",
            inputExtra = extra,
            prompt = "my prompt",
            nPredict = 64,
            nIndent = 4,
            topK = 20,
            topP = 0.5,
            samplers = listOf("top_k"),
            stream = true,
            cachePrompt = false,
            tMaxPromptMs = 1000,
            tMaxPredictMs = 500,
            responseFields = listOf("content"),
            temperature = 0.7,
            stop = listOf("\n", "```")
        )
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(3, (obj["id_slot"] as JsonPrimitive).int)
        assertEquals("before cursor", (obj["input_prefix"] as JsonPrimitive).content)
        assertEquals("after cursor", (obj["input_suffix"] as JsonPrimitive).content)
        assertEquals("my prompt", (obj["prompt"] as JsonPrimitive).content)
        assertEquals(64, (obj["n_predict"] as JsonPrimitive).int)
        assertEquals(4, (obj["n_indent"] as JsonPrimitive).int)
        assertEquals(20, (obj["top_k"] as JsonPrimitive).int)
        assertEquals(0.5, (obj["top_p"] as JsonPrimitive).double, 1e-9)
        assertEquals(true, (obj["stream"] as JsonPrimitive).boolean)
        assertEquals(false, (obj["cache_prompt"] as JsonPrimitive).boolean)
        assertEquals(1000, (obj["t_max_prompt_ms"] as JsonPrimitive).int)
        assertEquals(500, (obj["t_max_predict_ms"] as JsonPrimitive).int)
        assertEquals(0.7, (obj["temperature"] as JsonPrimitive).double, 1e-9)

        val extraArr = obj["input_extra"]!!.jsonArray
        assertEquals(1, extraArr.size)
        val extraObj = extraArr[0].jsonObject
        assertEquals("foo.kt", (extraObj["filename"] as JsonPrimitive).content)
        assertEquals("some code", (extraObj["text"] as JsonPrimitive).content)

        val stop = obj["stop"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("\n", "```"), stop)
    }

    // --- InfillResponse deserialization ---

    fun testInfillResponseDeserialization() {
        val jsonStr = """
            {
                "content": "hello world",
                "timings/prompt_n": 42,
                "timings/predicted_n": 10,
                "timings/predicted_ms": 123.45,
                "truncated": true,
                "tokens_cached": 100
            }
        """.trimIndent()
        val response = json.decodeFromString<InfillResponse>(jsonStr)

        assertEquals("hello world", response.content)
        assertEquals(42, response.promptN)
        assertEquals(10, response.predictedN)
        assertEquals(123.45, response.predictedMs, 1e-9)
        assertEquals(true, response.truncated)
        assertEquals(100, response.tokensCached)
    }

    fun testInfillResponseDeserializationMissingOptionalFields() {
        val jsonStr = "{}"
        val response = json.decodeFromString<InfillResponse>(jsonStr)

        assertEquals("", response.content)
        assertEquals(0, response.promptN)
        assertEquals(0, response.predictedN)
        assertEquals(0.0, response.predictedMs, 1e-9)
        assertEquals(false, response.truncated)
        assertEquals(0, response.tokensCached)
    }

    // --- buildCacheWarmingRequest ---

    fun testBuildCacheWarmingRequestShape() {
        val request = buildCacheWarmingRequest(emptyList())

        assertEquals("", request.inputPrefix)
        assertEquals("", request.inputSuffix)
        assertEquals("", request.prompt)
        assertEquals(0, request.nPredict)
        assertEquals(1, request.tMaxPromptMs)
        assertEquals(1, request.tMaxPredictMs)
        assertEquals(emptyList<String>(), request.samplers)
        assertEquals(listOf(""), request.responseFields)
        assertEquals(0.0, request.temperature, 1e-9)
        assertEquals(true, request.cachePrompt)
        assertEquals(emptyList<InfillExtraChunk>(), request.inputExtra)
    }

    fun testBuildCacheWarmingRequestJsonWireFormat() {
        val request = buildCacheWarmingRequest(emptyList())
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(0, (obj["n_predict"] as JsonPrimitive).int)
        assertEquals(0, obj["samplers"]!!.jsonArray.size)
        val responseFields = obj["response_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(""), responseFields)
        assertEquals(1, (obj["t_max_prompt_ms"] as JsonPrimitive).int)
        assertEquals(1, (obj["t_max_predict_ms"] as JsonPrimitive).int)
        assertEquals(true, (obj["cache_prompt"] as JsonPrimitive).boolean)
    }

    fun testBuildCacheWarmingRequestPreservesExtraChunks() {
        val extra = listOf(
            InfillExtraChunk(filename = "a.kt", text = "code a"),
            InfillExtraChunk(filename = "b.kt", text = "code b")
        )
        val request = buildCacheWarmingRequest(extra)

        assertEquals(2, request.inputExtra.size)
        assertEquals("a.kt", request.inputExtra[0].filename)
        assertEquals("code a", request.inputExtra[0].text)
        assertEquals("b.kt", request.inputExtra[1].filename)
        assertEquals("code b", request.inputExtra[1].text)
    }

    // --- Round-trip ---

    fun testRoundTripSerializationKeyFieldsPresent() {
        val request = InfillRequest(
            inputPrefix = "pre",
            inputSuffix = "suf",
            nPredict = 64
        )
        val jsonStr = json.encodeToString(request)
        val deserialized = json.decodeFromString<InfillRequest>(jsonStr)

        assertEquals(request, deserialized)
    }

    // --- stop field ---

    fun testStopFieldDefaultSerializesToEmptyArray() {
        val request = InfillRequest(inputPrefix = "", inputSuffix = "")
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        val stop = obj["stop"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(emptyList<String>(), stop)
    }

    fun testStopFieldCustomValues() {
        val request = InfillRequest(inputPrefix = "", inputSuffix = "", stop = listOf("\n", "```"))
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        val stop = obj["stop"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("\n", "```"), stop)
    }

    // --- Edge cases ---

    fun testInfillRequestEmptyInputExtra() {
        val request = InfillRequest(inputPrefix = "", inputSuffix = "")
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(0, obj["input_extra"]!!.jsonArray.size)
    }

    fun testInfillRequestEmptyStrings() {
        val request = InfillRequest(inputPrefix = "", inputSuffix = "", prompt = "")
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals("", (obj["input_prefix"] as JsonPrimitive).content)
        assertEquals("", (obj["input_suffix"] as JsonPrimitive).content)
        assertEquals("", (obj["prompt"] as JsonPrimitive).content)
    }

    fun testInfillRequestZeroValues() {
        val request = InfillRequest(
            inputPrefix = "",
            inputSuffix = "",
            nPredict = 0,
            nIndent = 0,
            topK = 0,
            topP = 0.0,
            temperature = 0.0
        )
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(0, (obj["n_predict"] as JsonPrimitive).int)
        assertEquals(0, (obj["n_indent"] as JsonPrimitive).int)
        assertEquals(0, (obj["top_k"] as JsonPrimitive).int)
        assertEquals(0.0, (obj["top_p"] as JsonPrimitive).double, 1e-9)
        assertEquals(0.0, (obj["temperature"] as JsonPrimitive).double, 1e-9)
    }

    // --- InfillExtraChunk standalone serialization ---

    fun testInfillExtraChunkSerialization() {
        val chunk = InfillExtraChunk(filename = "hello.kt", text = "fun main() {}")
        val jsonStr = json.encodeToString(chunk)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals("hello.kt", (obj["filename"] as JsonPrimitive).content)
        assertEquals("fun main() {}", (obj["text"] as JsonPrimitive).content)
        assertEquals(2, obj.size)
    }

    fun testInfillExtraChunkDeserialization() {
        val jsonStr = """{"filename":"bar.py","text":"pass"}"""
        val chunk = json.decodeFromString<InfillExtraChunk>(jsonStr)

        assertEquals("bar.py", chunk.filename)
        assertEquals("pass", chunk.text)
    }

    // --- ignoreUnknownKeys exercise ---

    fun testInfillResponseIgnoreUnknownKeys() {
        val jsonStr = """
            {
                "content": "result",
                "timings/prompt_n": 5,
                "timings/predicted_n": 3,
                "timings/predicted_ms": 50.0,
                "truncated": false,
                "tokens_cached": 10,
                "model": "qwen",
                "index": 0,
                "extra_field": "should be ignored"
            }
        """.trimIndent()
        val response = json.decodeFromString<InfillResponse>(jsonStr)

        assertEquals("result", response.content)
        assertEquals(5, response.promptN)
        assertEquals(3, response.predictedN)
        assertEquals(50.0, response.predictedMs, 1e-9)
        assertEquals(false, response.truncated)
        assertEquals(10, response.tokensCached)
    }

    // --- Warming request with non-empty extra JSON wire format ---

    fun testBuildCacheWarmingRequestWithChunksJsonWireFormat() {
        val extra = listOf(
            InfillExtraChunk(filename = "a.kt", text = "code a"),
            InfillExtraChunk(filename = "b.kt", text = "code b")
        )
        val request = buildCacheWarmingRequest(extra)
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        val inputExtra = obj["input_extra"]!!.jsonArray
        assertEquals(2, inputExtra.size)
        assertEquals("a.kt", inputExtra[0].jsonObject["filename"]!!.jsonPrimitive.content)
        assertEquals("code a", inputExtra[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("b.kt", inputExtra[1].jsonObject["filename"]!!.jsonPrimitive.content)
        assertEquals("code b", inputExtra[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // --- Partial InfillResponse (only some fields present) ---

    fun testInfillResponsePartialFields() {
        val jsonStr = """
            {
                "content": "partial",
                "truncated": true
            }
        """.trimIndent()
        val response = json.decodeFromString<InfillResponse>(jsonStr)

        assertEquals("partial", response.content)
        assertEquals(true, response.truncated)
        // Timing fields absent — verify defaults are used
        assertEquals(0, response.promptN)
        assertEquals(0, response.predictedN)
        assertEquals(0.0, response.predictedMs, 1e-9)
        assertEquals(0, response.tokensCached)
    }

    // --- InfillRequest with multiple extra chunks ---

    fun testInfillRequestMultipleExtraChunksSerialization() {
        val extra = listOf(
            InfillExtraChunk(filename = "a.kt", text = "code a"),
            InfillExtraChunk(filename = "b.kt", text = "code b"),
            InfillExtraChunk(filename = "c.kt", text = "code c")
        )
        val request = InfillRequest(inputPrefix = "pre", inputSuffix = "suf", inputExtra = extra)
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        val inputExtra = obj["input_extra"]!!.jsonArray
        assertEquals(3, inputExtra.size)
        assertEquals("a.kt", inputExtra[0].jsonObject["filename"]!!.jsonPrimitive.content)
        assertEquals("code a", inputExtra[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("b.kt", inputExtra[1].jsonObject["filename"]!!.jsonPrimitive.content)
        assertEquals("code b", inputExtra[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("c.kt", inputExtra[2].jsonObject["filename"]!!.jsonPrimitive.content)
        assertEquals("code c", inputExtra[2].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // --- Round-trip for buildCacheWarmingRequest output ---

    fun testBuildCacheWarmingRequestRoundTrip() {
        val extra = listOf(
            InfillExtraChunk(filename = "x.kt", text = "val x = 1")
        )
        val original = buildCacheWarmingRequest(extra)
        val jsonStr = json.encodeToString(original)
        val deserialized = json.decodeFromString<InfillRequest>(jsonStr)

        assertEquals(original, deserialized)
    }
}
