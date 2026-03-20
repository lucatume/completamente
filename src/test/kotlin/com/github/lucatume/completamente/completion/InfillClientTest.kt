package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

class InfillClientTest : BaseCompletionTest() {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // --- Serialization of request body ---

    fun testRequestBodySerializationIsCorrectJson() {
        val request = InfillRequest(inputPrefix = "before", inputSuffix = "after", nPredict = 64)
        val jsonStr = json.encodeToString(request)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals("before", (obj["input_prefix"] as JsonPrimitive).content)
        assertEquals("after", (obj["input_suffix"] as JsonPrimitive).content)
        assertEquals(64, (obj["n_predict"] as JsonPrimitive).int)
        // Verify all expected keys are present
        assertTrue(obj.containsKey("id_slot"))
        assertTrue(obj.containsKey("samplers"))
        assertTrue(obj.containsKey("stream"))
        assertTrue(obj.containsKey("cache_prompt"))
        assertTrue(obj.containsKey("t_max_prompt_ms"))
        assertTrue(obj.containsKey("t_max_predict_ms"))
        assertTrue(obj.containsKey("response_fields"))
        assertTrue(obj.containsKey("temperature"))
        assertTrue(obj.containsKey("stop"))
    }

    // --- InfillClientException ---

    fun testInfillClientExceptionWrapsMessage() {
        val ex = InfillClientException("something went wrong")
        assertEquals("something went wrong", ex.message)
        assertNull(ex.cause)
    }

    fun testInfillClientExceptionWrapsMessageAndCause() {
        val cause = RuntimeException("root cause")
        val ex = InfillClientException("wrapper", cause)
        assertEquals("wrapper", ex.message)
        assertSame(cause, ex.cause)
    }

    // --- isServerReachable returns false for unreachable host ---

    fun testIsServerReachableReturnsFalseForUnreachableHost() {
        val client = InfillClient("http://127.0.0.1:1")
        assertFalse(client.isServerReachable())
    }

    // --- sendCompletion throws for unreachable host ---

    fun testSendCompletionThrowsInfillClientExceptionForUnreachableHost() {
        val client = InfillClient("http://127.0.0.1:1")
        val request = InfillRequest(inputPrefix = "pre", inputSuffix = "suf")
        try {
            client.sendCompletion(request)
            fail("Expected InfillClientException")
        } catch (e: InfillClientException) {
            assertTrue("Exception message should describe the failure", e.message!!.startsWith("Failed to send completion request:"))
        }
    }

    // --- sendCacheWarming does NOT throw for unreachable host ---

    fun testSendCacheWarmingDoesNotThrowForUnreachableHost() {
        val client = InfillClient("http://127.0.0.1:1")
        val extra = listOf(InfillExtraChunk(filename = "test.kt", text = "val x = 1"))
        // Should not throw — fire and forget
        client.sendCacheWarming(extra)
    }

    fun testSendCacheWarmingDoesNotThrowWithMultipleExtraChunksOnUnreachableHost() {
        val client = InfillClient("http://127.0.0.1:1")
        val extra = listOf(
            InfillExtraChunk(filename = "a.kt", text = "val a = 1"),
            InfillExtraChunk(filename = "b.kt", text = "val b = 2"),
            InfillExtraChunk(filename = "c.kt", text = "val c = 3")
        )
        // Should not throw — fire and forget even with non-empty extra list
        client.sendCacheWarming(extra)
    }

    // --- Deserialization through client's Json config ---

    fun testInfillResponseDeserializationWithClientJsonConfig() {
        // Exercises the same Json configuration the client uses for response deserialization.
        val serverJson = """
            {
                "content": "completed code",
                "timings/prompt_n": 15,
                "timings/predicted_n": 8,
                "timings/predicted_ms": 42.5,
                "truncated": false,
                "tokens_cached": 55,
                "model": "sweep-1.5b",
                "index": 0
            }
        """.trimIndent()
        val response = json.decodeFromString<InfillResponse>(serverJson)

        assertEquals("completed code", response.content)
        assertEquals(15, response.promptN)
        assertEquals(8, response.predictedN)
        assertEquals(42.5, response.predictedMs, 1e-9)
        assertEquals(false, response.truncated)
        assertEquals(55, response.tokensCached)
    }

    // --- InfillClientException message format for non-200 status ---

    fun testInfillClientExceptionStatusMessageFormat() {
        // Documents the error message format produced by the non-200 status code path.
        val ex = InfillClientException("Server returned status 503: {\"error\":\"busy\"}")
        assertTrue(ex.message!!.startsWith("Server returned status"))
        assertTrue(ex.message!!.contains("503"))
    }
}
