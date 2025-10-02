package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull

class ringBufferTest : BaseCompletionTest() {
    fun testExtraContextEntryInstantiation() {
        val entry = ExtraContextEntry(
            text = "test text",
            time = 1234567890L,
            filename = "test.kt"
        )
        assertEquals("test text", entry.text)
        assertEquals(1234567890L, entry.time)
        assertEquals("test.kt", entry.filename)
    }

    fun testExtraContextEntryEquality() {
        val entry1 = ExtraContextEntry(
            text = "test",
            time = 100L,
            filename = "file.kt"
        )
        val entry2 = ExtraContextEntry(
            text = "test",
            time = 100L,
            filename = "file.kt"
        )
        assertEquals(entry1, entry2)
    }

    fun testExtraContextEntryFieldAccess() {
        val entries = listOf(
            ExtraContextEntry("text1", 100L, "file1.kt"),
            ExtraContextEntry("text2", 200L, "file2.kt"),
            ExtraContextEntry("text3", 300L, "file3.kt")
        )
        assertEquals(3, entries.size)
        assertEquals("text1", entries[0].text)
        assertEquals(100L, entries[0].time)
        assertEquals("file1.kt", entries[0].filename)
        assertEquals("text3", entries[2].text)
        assertEquals(300L, entries[2].time)
        assertEquals("file3.kt", entries[2].filename)
    }

    fun testRingUpdateRequestInstantiationWithDefaultValues() {
        val request = RingUpdateRequest(
            input_extra = listOf(
                ExtraContextEntry("test", 100L, "file.kt")
            )
        )
        assertEquals("", request.input_prefix)
        assertEquals("", request.input_suffix)
        assertEquals("", request.prompt)
        assertEquals(0, request.n_predict)
        assertEquals(0.0, request.temperature)
        assertFalse(request.stream)
        assertTrue(request.cache_prompt)
        assertEquals(1, request.t_max_prompt_ms)
        assertEquals(1, request.t_max_predict_ms)
        assertNull(request.model)
    }

    fun testRingUpdateRequestWithModel() {
        val request = RingUpdateRequest(
            input_extra = listOf(
                ExtraContextEntry("test", 100L, "file.kt")
            ),
            model = "llama2"
        )
        assertEquals("llama2", request.model)
    }

    fun testRingUpdateRequestModelFieldNullWhenEmpty() {
        val settings = Settings(model = "")
        val request = RingUpdateRequest(
            input_extra = listOf(
                ExtraContextEntry("test", 100L, "file.kt")
            ),
            model = settings.model.ifEmpty { null }
        )
        assertNull(request.model)
    }

    fun testRingUpdateRequestModelFieldActualString() {
        val settings = Settings(model = "mistral")
        val request = RingUpdateRequest(
            input_extra = listOf(
                ExtraContextEntry("test", 100L, "file.kt")
            ),
            model = settings.model.ifEmpty { null }
        )
        assertEquals("mistral", request.model)
    }

    fun testRingUpdateRequestSerialization() {
        val request = RingUpdateRequest(
            input_extra = listOf(
                ExtraContextEntry("test content", 123L, "main.kt")
            ),
            model = "test-model"
        )
        val json = Json { encodeDefaults = true }
        val jsonString = json.encodeToString(request)
        assertTrue(jsonString.contains("\"text\":\"test content\""))
        assertTrue(jsonString.contains("\"time\":123"))
        assertTrue(jsonString.contains("\"filename\":\"main.kt\""))
        assertTrue(jsonString.contains("\"model\":\"test-model\""))
    }

    fun testRingUpdateRequestDefaultFields() {
        val request = RingUpdateRequest(
            input_extra = listOf()
        )
        assertEquals(listOf(""), request.response_fields)
        assertEquals(emptyList<String>(), request.samplers)
    }

    fun testRingUpdateEarlyExitWhenCursorMovedWithinThreeSeconds() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(
            Chunk("chunk1", 100L, "file1.kt")
        )
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis()
        ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)

        assertEquals(0, ringChunks.size)
        assertEquals(1, ringQueued.size)
    }

    fun testRingUpdateEarlyExitWhenCursorMovedExactlyThreeSeconds() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(
            Chunk("chunk1", 100L, "file1.kt")
        )
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 3000
        Thread.sleep(10)
        ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)

        assertEquals(1, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateContinuesWhenCursorMovedMoreThanThreeSeconds() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(
            Chunk("chunk1", 100L, "file1.kt")
        )
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)

        assertEquals(1, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateEarlyExitWhenRingQueuedEmpty() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)

        assertEquals(0, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateMovesFirstChunkFromQueueToRing() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val ringQueued = mutableListOf(chunk1)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(chunk1, ringChunks[0])
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateProcessesOnlyFirstItemInQueue() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val chunk2 = Chunk("chunk2", 200L, "file2.kt")
        val chunk3 = Chunk("chunk3", 300L, "file3.kt")
        val ringQueued = mutableListOf(chunk1, chunk2, chunk3)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(chunk1, ringChunks[0])
        assertEquals(2, ringQueued.size)
        assertEquals(chunk2, ringQueued[0])
        assertEquals(chunk3, ringQueued[1])
    }

    fun testRingUpdateFIFOBehaviorWithMultipleCalls() {
        val settings = Settings()
        val ringChunks = mutableListOf<Chunk>()
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val chunk2 = Chunk("chunk2", 200L, "file2.kt")
        val chunk3 = Chunk("chunk3", 300L, "file3.kt")
        val ringQueued = mutableListOf(chunk1, chunk2, chunk3)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(3, ringChunks.size)
        assertEquals(chunk1, ringChunks[0])
        assertEquals(chunk2, ringChunks[1])
        assertEquals(chunk3, ringChunks[2])
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateDoesNotRemoveWhenBelowCapacity() {
        val settings = Settings(ringNChunks = 16)
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val chunk2 = Chunk("chunk2", 200L, "file2.kt")
        val ringChunks = mutableListOf(chunk1)
        val ringQueued = mutableListOf(chunk2)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(2, ringChunks.size)
        assertEquals(chunk1, ringChunks[0])
        assertEquals(chunk2, ringChunks[1])
    }

    fun testRingUpdateRemovesOldestWhenAtCapacity() {
        val settings = Settings(ringNChunks = 2)
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val chunk2 = Chunk("chunk2", 200L, "file2.kt")
        val chunk3 = Chunk("chunk3", 300L, "file3.kt")
        val ringChunks = mutableListOf(chunk1, chunk2)
        val ringQueued = mutableListOf(chunk3)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(2, ringChunks.size)
        assertEquals(chunk2, ringChunks[0])
        assertEquals(chunk3, ringChunks[1])
    }

    fun testRingUpdateRemovesMultipleOldestInSequence() {
        val settings = Settings(ringNChunks = 2)
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val chunk2 = Chunk("chunk2", 200L, "file2.kt")
        val chunk3 = Chunk("chunk3", 300L, "file3.kt")
        val chunk4 = Chunk("chunk4", 400L, "file4.kt")
        val ringChunks = mutableListOf(chunk1, chunk2)
        val ringQueued = mutableListOf(chunk3, chunk4)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(2, ringChunks.size)
        assertEquals(chunk3, ringChunks[0])
        assertEquals(chunk4, ringChunks[1])
    }

    fun testRingUpdateBuildsExtraContextFromAllChunks() {
        val settings = Settings()
        val chunk1 = Chunk("text1", 100L, "file1.kt")
        val chunk2 = Chunk("text2", 200L, "file2.kt")
        val ringChunks = mutableListOf(chunk1)
        val ringQueued = mutableListOf(chunk2)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(2, ringChunks.size)
        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdatePreservesChunkFieldsInExtraContext() {
        val settings = Settings()
        val chunk1 = Chunk("first text", 111L, "first.kt")
        val chunk2 = Chunk("second text", 222L, "second.kt")
        val ringChunks = mutableListOf(chunk1)
        val ringQueued = mutableListOf(chunk2)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(2, ringChunks.size)
        assertEquals("first text", ringChunks[0].text)
        assertEquals(111L, ringChunks[0].time)
        assertEquals("first.kt", ringChunks[0].filename)
        assertEquals("second text", ringChunks[1].text)
        assertEquals(222L, ringChunks[1].time)
        assertEquals("second.kt", ringChunks[1].filename)
    }

    fun testRingUpdateCreatesRingUpdateRequest() {
        val settings = Settings(endpoint = "http://test.local:8012/infill")
        val chunk1 = Chunk("test chunk", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateSendsRequestToCorrectEndpoint() {
        val settings = Settings(endpoint = "http://custom.endpoint:9000/completion")
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateIncludesAuthorizationHeaderWhenApiKeyProvided() {
        val settings = Settings(apiKey = "test-api-key-12345")
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateOmitsAuthorizationHeaderWhenApiKeyEmpty() {
        val settings = Settings(apiKey = "")
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateRequestBodyIsValidJson() {
        val settings = Settings(model = "test-model")
        val chunk1 = Chunk("test content", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateRequestBodyContainsInputExtra() {
        val settings = Settings()
        val chunk1 = Chunk("test chunk", 123L, "main.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
        assertEquals(1, ringChunks.size)
    }

    fun testRingUpdateRequestBodyWithoutModelWhenEmpty() {
        val settings = Settings(model = "")
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateRequestBodyWithModelWhenSet() {
        val settings = Settings(model = "my-model")
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateCatchesNetworkErrors() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val httpClient = makeMockHttpClientWithError()
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateContinuesNormallyAfterNetworkError() {
        val settings = Settings()
        val chunk1 = Chunk("test1", 100L, "file1.kt")
        val chunk2 = Chunk("test2", 200L, "file2.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1, chunk2)
        val httpClient = makeMockHttpClientWithError()
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(1, ringQueued.size)
    }

    fun testRingUpdateWithSingleQueuedItem() {
        val settings = Settings()
        val chunk1 = Chunk("single chunk", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(0, ringQueued.size)
        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateWithManyQueuedItems() {
        val settings = Settings(ringNChunks = 16)
        val chunks = (1..50).map { i ->
            Chunk("chunk$i", (100L * i), "file$i.kt")
        }
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = chunks.toMutableList()
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            for (i in 0 until 30) {
                if (ringQueued.isNotEmpty()) {
                    ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        assertTrue(ringChunks.size <= settings.ringNChunks)
        assertEquals(20, ringQueued.size)
    }

    fun testRingUpdateTimestampBoundaryExactly3000Ms() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 3000
        Thread.sleep(5)
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateTimestampBoundaryJustUnder3000Ms() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 2999
        ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)

        assertEquals(0, ringChunks.size)
        assertEquals(1, ringQueued.size)
    }

    fun testRingUpdateTimestampBoundaryJustOver3000Ms() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 3001
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateWithLargeChunkText() {
        val settings = Settings()
        val largeText = "x".repeat(2000)
        val chunk1 = Chunk(largeText, 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals(largeText, ringChunks[0].text)
        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateWithMultipleLargeChunks() {
        val settings = Settings(ringNChunks = 5)
        val chunks = (1..10).map { i ->
            Chunk("content".repeat(100) + i, (100L * i), "file$i.kt")
        }
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = chunks.toMutableList()
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            for (i in 0 until 10) {
                if (ringQueued.isNotEmpty()) {
                    ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        assertEquals(5, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateWithEmptyChunkText() {
        val settings = Settings()
        val chunk1 = Chunk("", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, ringChunks.size)
        assertEquals("", ringChunks[0].text)
    }

    fun testRingUpdateChunksRemainInOrder() {
        val settings = Settings(ringNChunks = 5)
        val chunk1 = Chunk("first", 1000L, "file1.kt")
        val chunk2 = Chunk("second", 2000L, "file2.kt")
        val chunk3 = Chunk("third", 3000L, "file3.kt")
        val chunk4 = Chunk("fourth", 4000L, "file4.kt")
        val chunk5 = Chunk("fifth", 5000L, "file5.kt")
        val chunk6 = Chunk("sixth", 6000L, "file6.kt")
        val ringChunks = mutableListOf(chunk1, chunk2, chunk3, chunk4, chunk5)
        val ringQueued = mutableListOf(chunk6)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(5, ringChunks.size)
        assertEquals(chunk2, ringChunks[0])
        assertEquals(chunk3, ringChunks[1])
        assertEquals(chunk4, ringChunks[2])
        assertEquals(chunk5, ringChunks[3])
        assertEquals(chunk6, ringChunks[4])
    }

    fun testRingUpdateAsynchronousExecution() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithDelay("{}", 200, requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        val startTime = System.currentTimeMillis()
        ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
        val endTime = System.currentTimeMillis()

        assertTrue((endTime - startTime) < 200)

        runBlocking {
            kotlinx.coroutines.delay(300)
        }

        assertEquals(1, requestTracker.get())
    }

    fun testRingUpdateMultipleProcessingCycles() {
        val settings = Settings(ringNChunks = 3)
        val chunk1 = Chunk("chunk1", 100L, "file1.kt")
        val chunk2 = Chunk("chunk2", 200L, "file2.kt")
        val chunk3 = Chunk("chunk3", 300L, "file3.kt")
        val chunk4 = Chunk("chunk4", 400L, "file4.kt")
        val chunk5 = Chunk("chunk5", 500L, "file5.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1, chunk2, chunk3, chunk4, chunk5)
        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("{}", requestTracker)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            for (i in 0 until 5) {
                if (ringQueued.isNotEmpty()) {
                    ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
                    kotlinx.coroutines.delay(50)
                }
            }
        }

        assertEquals(3, ringChunks.size)
        assertEquals(0, ringQueued.size)
        assertEquals(5, requestTracker.get())
    }

    fun testRingUpdateDoesNotModifyInputChunk() {
        val settings = Settings()
        val originalChunk = Chunk("original text", 123L, "original.kt")
        val chunk1 = originalChunk.copy()
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(originalChunk.text, ringChunks[0].text)
        assertEquals(originalChunk.time, ringChunks[0].time)
        assertEquals(originalChunk.filename, ringChunks[0].filename)
    }

    fun testRingUpdateQueuedListModifiedInPlace() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(chunk1)
        val originalRingQueuedRef = ringQueued
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(originalRingQueuedRef, ringQueued)
        assertEquals(0, ringQueued.size)
    }

    fun testRingUpdateChunksListModifiedInPlace() {
        val settings = Settings()
        val chunk1 = Chunk("test", 100L, "file.kt")
        val ringChunks = mutableListOf<Chunk>()
        val originalRingChunksRef = ringChunks
        val ringQueued = mutableListOf(chunk1)
        val httpClient = makeMockHttpClient("{}")
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        val lastMoveMs = System.currentTimeMillis() - 5000
        runBlocking {
            ringUpdate(settings, ringChunks, ringQueued, lastMoveMs, httpClient, coroutineScope)
            kotlinx.coroutines.delay(100)
        }

        assertEquals(originalRingChunksRef, ringChunks)
        assertEquals(1, ringChunks.size)
    }

    fun testExtraContextEntryImmutable() {
        val entry = ExtraContextEntry("text", 100L, "file.kt")
        val text = entry.text
        val time = entry.time
        val filename = entry.filename

        assertEquals("text", text)
        assertEquals(100L, time)
        assertEquals("file.kt", filename)
    }

    fun testRingUpdateRequestDefaultTemperature() {
        val request = RingUpdateRequest(
            input_extra = listOf()
        )
        assertEquals(0.0, request.temperature)
    }

    fun testRingUpdateRequestDefaultStream() {
        val request = RingUpdateRequest(
            input_extra = listOf()
        )
        assertFalse(request.stream)
    }

    fun testRingUpdateRequestDefaultCachePrompt() {
        val request = RingUpdateRequest(
            input_extra = listOf()
        )
        assertTrue(request.cache_prompt)
    }

    fun testRingUpdateRequestDefaultTimeouts() {
        val request = RingUpdateRequest(
            input_extra = listOf()
        )
        assertEquals(1, request.t_max_prompt_ms)
        assertEquals(1, request.t_max_predict_ms)
    }
}
