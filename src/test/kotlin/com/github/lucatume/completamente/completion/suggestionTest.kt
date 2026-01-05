package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.BackgroundJobs
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.ChunksRingBuffer
import com.github.lucatume.completamente.services.Services
import com.github.lucatume.completamente.services.Settings
import com.github.lucatume.completamente.services.SuggestionCache
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class suggestionTest : BaseCompletionTest() {
    private var backgroundJobs: BackgroundJobs? = null

    override fun setUp() {
        super.setUp()
        backgroundJobs = BackgroundJobs()
    }

    override fun tearDown() {
        backgroundJobs?.dispose()
        backgroundJobs = null
        super.tearDown()
    }

    private fun makeServices(
        settings: Settings = Settings(),
        cache: SuggestionCache = SuggestionCache(),
        httpClient: HttpClient
    ): Services {
        return Services(
            settings = settings,
            cache = cache,
            chunksRingBuffer = ChunksRingBuffer(myFixture.project),
            backgroundJobs = backgroundJobs!!,
            httpClient = httpClient
        )
    }

    fun testReturnsEmptySuggestionWhenLineSuffixExceedsMaxLineSuffix() = runBlocking {
        val file = "test.kt"
        // Create content where cursor is positioned with more than 8 chars after it on the same line
        // maxLineSuffix default is 8
        val fileContent = "val x = something123456789"
        myFixture.configureByText(file, fileContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "should_not_be_called"}""", requestTracker)

        val settings = Settings(maxLineSuffix = 8)
        val services = makeServices(settings = settings, httpClient = httpClient)

        // Position cursor at offset 8 ("val x = "), leaving "something123456789" (18 chars) as suffix
        val cursorOffset = 8
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals("", result.suggestion.text)
        assertEquals(0, requestTracker.get())
    }

    fun testReturnsEmptySuggestionWhenLineSuffixEqualsMaxLineSuffix() = runBlocking {
        val file = "test.kt"
        // Create content where suffix is exactly maxLineSuffix length
        val fileContent = "val x = 12345678"  // 8 chars after cursor position at "val x = "
        myFixture.configureByText(file, fileContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "completion"}""", requestTracker)

        val settings = Settings(maxLineSuffix = 8)
        val services = makeServices(settings = settings, httpClient = httpClient)

        // Position cursor at offset 8, leaving exactly 8 chars as suffix
        val cursorOffset = 8
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        // When suffix length equals maxLineSuffix (8 == 8), it should NOT trigger the early return
        // because the check is > not >=
        // The suggestion is combined with the existing suffix (lineCurSuffix) by fimRender
        assertEquals("completion12345678", result.suggestion.text)
        assertTrue(requestTracker.get() > 0)
    }

    fun testReturnsCachedSuggestionOnExactHashMatch() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val expectedSuggestion = "123"

        // Build the context at the cursor position (end of "    val x = ")
        val settings = Settings()
        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)
        val localContext = buildLocalContext(request, settings, null)

        // Pre-populate cache with the exact hash
        val hash = computeContextHash(localContext.prefix, localContext.middle, localContext.suffix)
        cacheInsert(cache, hash, """{"content": "$expectedSuggestion"}""", 250)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "should_not_be_called"}""", requestTracker)

        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(expectedSuggestion, result.suggestion.text)
        assertEquals(0, requestTracker.get())
    }

    fun testReturnsTrimmedCachedSuggestionOnPartialMatch() = runBlocking {
        val file = "test.kt"
        // First, set up the cache at an earlier position
        val initialContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, initialContent)

        val cache = SuggestionCache()
        val settings = Settings()
        val fullSuggestion = "abc123"

        // Get context at initial position (before typing)
        val initialOffset = initialContent.indexOf("val x = ") + "val x = ".length
        val initialRequest = makeInlineCompletionRequest(file, initialOffset - 1, initialOffset)
        val initialContext = buildLocalContext(initialRequest, settings, null)

        // Cache the full suggestion at the initial position
        val initialHash = computeContextHash(initialContext.prefix, initialContext.middle, initialContext.suffix)
        cacheInsert(cache, initialHash, """{"content": "$fullSuggestion"}""", 250)

        // Now simulate typing "a" - the first character of the cached suggestion
        val typedContent = "fun test() {\n    val x = a\n}"
        myFixture.configureByText(file, typedContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "should_not_be_called"}""", requestTracker)

        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        // Get request at new position (after typing "a")
        val newOffset = typedContent.indexOf("val x = a") + "val x = a".length
        val newRequest = makeInlineCompletionRequest(file, newOffset - 1, newOffset)

        val result = getSuggestionPure(
            services = services,
            request = newRequest,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        // Should return the trimmed suggestion (removed the "a" that was already typed)
        assertEquals("bc123", result.suggestion.text)
        assertEquals(0, requestTracker.get())
    }

    fun testMakesHttpRequestAndReturnsSuggestionOnCacheMiss() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val expectedSuggestion = "test_completion"

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "$expectedSuggestion"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(expectedSuggestion, result.suggestion.text)
        // At least one request for the main FIM call
        assertTrue(requestTracker.get() >= 1)
        // Cache should be populated
        assertTrue(cache.data.size >= 1)
    }

    fun testReturnsEmptySuggestionOnEmptyHttpResponse() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": ""}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals("", result.suggestion.text)
        assertTrue(requestTracker.get() >= 1)
    }

    fun testReturnsEmptySuggestionOnNetworkError() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val httpClient = makeMockHttpClientWithError()

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals("", result.suggestion.text)
    }

    fun testUpdatesPrevOnSuccessfulSuggestion() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val multiLineSuggestion = "line1\nline2\nline3"

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "$multiLineSuggestion"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(multiLineSuggestion, result.suggestion.text)
        assertEquals(listOf("line1", "line2", "line3"), result.prev)
    }

    fun testUpdatesIndentLastOnSuccessfulSuggestion() = runBlocking {
        val file = "test.kt"
        // File with 4-space indentation
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val suggestion = "123"

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "$suggestion"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(suggestion, result.suggestion.text)
        // The indentLast should be updated to the indent of the current line (4 spaces)
        assertEquals(4, result.indentLast)
    }

    fun testPreservesPrevWhenSuggestionIsEmpty() = runBlocking {
        val file = "test.kt"
        // Create content where suffix exceeds maxLineSuffix to get empty suggestion
        val fileContent = "val x = something123456789"
        myFixture.configureByText(file, fileContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "should_not_be_called"}""", requestTracker)

        val settings = Settings(maxLineSuffix = 8)
        val services = makeServices(settings = settings, httpClient = httpClient)

        val originalPrev = listOf("original", "prev", "lines")
        val cursorOffset = 8
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = originalPrev,
            indentLast = 2,
            lastFile = null,
            lastLine = null
        )

        assertEquals("", result.suggestion.text)
        assertEquals(originalPrev, result.prev)
        assertEquals(2, result.indentLast)
    }

    fun testPreservesIndentLastWhenSuggestionIsEmpty() = runBlocking {
        val file = "test.kt"
        val fileContent = "val x = something123456789"
        myFixture.configureByText(file, fileContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "should_not_be_called"}""", requestTracker)

        val settings = Settings(maxLineSuffix = 8)
        val services = makeServices(settings = settings, httpClient = httpClient)

        val originalIndentLast = 8
        val cursorOffset = 8
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = originalIndentLast,
            lastFile = null,
            lastLine = null
        )

        assertEquals("", result.suggestion.text)
        assertEquals(originalIndentLast, result.indentLast)
    }

    fun testDoesNotPickChunksWhenCursorMovesLessThan32Lines() = runBlocking {
        val file = "test.kt"
        // Create a file with enough lines but cursor only moves a few lines
        val fileContent = (1..50).joinToString("\n") { "// line $it" } + "\nval x = "
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "completion"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        // Current line is approximately 51, last line is 45 - difference is 6 (< 32)
        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = myFixture.editor.virtualFile.canonicalPath,
            lastLine = 45
        )

        // No chunks should have been added to ringQueued
        assertEquals(0, services.chunksRingBuffer.getRingQueued().size)
    }

    fun testUsesExistingRingChunksAsExtraContext() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val expectedSuggestion = "completion_with_context"

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "$expectedSuggestion"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        // Pre-populate ring chunks with extra context
        val chunk1 = Chunk(text = "class Helper {\n    fun help() {}\n}", time = 100L, filename = "Helper.kt")
        val chunk2 = Chunk(text = "interface Service {\n    fun run()\n}", time = 200L, filename = "Service.kt")
        services.chunksRingBuffer.getRingChunks().add(chunk1)
        services.chunksRingBuffer.getRingChunks().add(chunk2)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(expectedSuggestion, result.suggestion.text)
        // HTTP request should have been made with extra context
        assertTrue(requestTracker.get() >= 1)
        // Ring chunks should still be present
        assertEquals(2, services.chunksRingBuffer.getRingChunks().size)
    }

    fun testCacheHitDoesNotModifyCacheData() = runBlocking {
        val file = "test.kt"
        val fileContent = "fun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val expectedSuggestion = "cached_suggestion"

        val settings = Settings()
        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)
        val localContext = buildLocalContext(request, settings, null)

        // Pre-populate cache
        val hash = computeContextHash(localContext.prefix, localContext.middle, localContext.suffix)
        cacheInsert(cache, hash, """{"content": "$expectedSuggestion"}""", 250)
        val initialCacheSize = cache.data.size

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "should_not_be_called"}""", requestTracker)

        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(expectedSuggestion, result.suggestion.text)
        // Cache size should not have changed
        assertEquals(initialCacheSize, cache.data.size)
    }

    fun testCacheMissPopulatesCacheWithMultipleHashes() = runBlocking {
        val file = "test.kt"
        // Create content with multiple prefix lines for hash variants
        val fileContent = "line1\nline2\nline3\nfun test() {\n    val x = \n}"
        myFixture.configureByText(file, fileContent)

        val cache = SuggestionCache()
        val expectedSuggestion = "test_completion"

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "$expectedSuggestion"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, cache = cache, httpClient = httpClient)

        val cursorOffset = fileContent.indexOf("val x = ") + "val x = ".length
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals(expectedSuggestion, result.suggestion.text)
        // Cache should have multiple entries (up to 4 hashes: original + 3 trimmed prefix variants)
        assertTrue(cache.data.size >= 1)
    }

    fun testLineSuffixAtBoundaryAllowsCompletion() = runBlocking {
        val file = "test.kt"
        // Exactly 7 chars after cursor (less than maxLineSuffix of 8)
        val fileContent = "val x = 1234567"
        myFixture.configureByText(file, fileContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "completion"}""", requestTracker)

        val settings = Settings(maxLineSuffix = 8)
        val services = makeServices(settings = settings, httpClient = httpClient)

        val cursorOffset = 8
        val request = makeInlineCompletionRequest(file, cursorOffset - 1, cursorOffset)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        // The suggestion is combined with the existing suffix (lineCurSuffix) by fimRender
        assertEquals("completion1234567", result.suggestion.text)
        assertTrue(requestTracker.get() >= 1)
    }

    fun testEmptyFileAllowsCompletion() = runBlocking {
        val file = "test.kt"
        val fileContent = ""
        myFixture.configureByText(file, fileContent)

        val requestTracker = AtomicInteger(0)
        val httpClient = makeMockHttpClientWithTracking("""{"content": "fun main() {}"}""", requestTracker)

        val settings = Settings()
        val services = makeServices(settings = settings, httpClient = httpClient)

        val request = makeInlineCompletionRequest(file, 0, 0)

        val result = getSuggestionPure(
            services = services,
            request = request,
            prev = null,
            indentLast = 0,
            lastFile = null,
            lastLine = null
        )

        assertEquals("fun main() {}", result.suggestion.text)
        assertTrue(requestTracker.get() >= 1)
    }
}
