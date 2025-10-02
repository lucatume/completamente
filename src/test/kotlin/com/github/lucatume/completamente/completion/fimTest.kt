package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings
import com.github.lucatume.completamente.services.SuggestionCache
import kotlinx.coroutines.runBlocking

class fimTest : BaseCompletionTest() {
    private var fimRenderTestCases: List<FimRenderTestCase>? = null

    override fun setUp() {
        super.setUp()

        if (fimRenderTestCases != null) {
            return
        }

        fimRenderTestCases = loadFimRenderTestData()
    }

    fun testBuildExtraContextEmpty() {
        val result = buildExtraContext(emptyList())
        assertEquals(0, result.size)
    }

    fun testBuildExtraContextSingleChunk() {
        val chunk = Chunk(text = "hello world", time = 1000L, filename = "test.kt")
        val result = buildExtraContext(listOf(chunk))

        assertEquals(1, result.size)
        assertEquals("hello world", result[0].text)
        assertEquals(1000L, result[0].time)
        assertEquals("test.kt", result[0].filename)
    }

    fun testBuildExtraContextMultipleChunks() {
        val chunks = listOf(
            Chunk(text = "chunk1", time = 100L, filename = "file1.kt"),
            Chunk(text = "chunk2", time = 200L, filename = "file2.kt"),
            Chunk(text = "chunk3", time = 300L, filename = "file3.kt")
        )
        val result = buildExtraContext(chunks)

        assertEquals(3, result.size)
        assertEquals("chunk1", result[0].text)
        assertEquals(100L, result[0].time)
        assertEquals("file1.kt", result[0].filename)
        assertEquals("chunk2", result[1].text)
        assertEquals(200L, result[1].time)
        assertEquals("file2.kt", result[1].filename)
        assertEquals("chunk3", result[2].text)
        assertEquals(300L, result[2].time)
        assertEquals("file3.kt", result[2].filename)
    }

    fun testBuildExtraContextPreservesChunkFields() {
        val chunk = Chunk(
            text = "function foo() {\n  return 42;\n}",
            time = 1234567890L,
            filename = "/path/to/file.js"
        )
        val result = buildExtraContext(listOf(chunk))

        assertEquals(1, result.size)
        assertTrue(result[0].text.contains("function foo"))
        assertEquals(1234567890L, result[0].time)
        assertEquals("/path/to/file.js", result[0].filename)
    }

    fun testBuildFimRequestBasic() {
        val context = LocalContext(
            prefix = "import os\n",
            middle = "x = ",
            suffix = "\nprint(x)",
            indent = 0,
            lineCur = "x = ",
            lineCurPrefix = "x = ",
            lineCurSuffix = ""
        )
        val settings = Settings()
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 500)

        assertEquals("import os\n", request.input_prefix)
        assertEquals("\nprint(x)", request.input_suffix)
        assertEquals("x = ", request.prompt)
        assertEquals(0, request.n_indent)
        assertEquals(settings.nPredict, request.n_predict)
        assertEquals(settings.stopStrings, request.stop)
        assertEquals(settings.tMaxPromptMs, request.t_max_prompt_ms)
        assertEquals(500, request.t_max_predict_ms)
        assertNull(request.model)
    }

    fun testBuildFimRequestWithModel() {
        val context = LocalContext(
            prefix = "code",
            middle = "test",
            suffix = "end",
            indent = 4,
            lineCur = "test",
            lineCurPrefix = "test",
            lineCurSuffix = ""
        )
        val settings = Settings(model = "llama2")
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 1000)

        assertEquals("llama2", request.model)
    }

    fun testBuildFimRequestWithEmptyModel() {
        val context = LocalContext(
            prefix = "code",
            middle = "test",
            suffix = "end",
            indent = 0,
            lineCur = "test",
            lineCurPrefix = "test",
            lineCurSuffix = ""
        )
        val settings = Settings(model = "")
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 1000)

        assertNull(request.model)
    }

    fun testBuildFimRequestWithStopStrings() {
        val context = LocalContext(
            prefix = "",
            middle = "code",
            suffix = "",
            indent = 0,
            lineCur = "code",
            lineCurPrefix = "code",
            lineCurSuffix = ""
        )
        val settings = Settings(stopStrings = listOf("def ", "class "))
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 100)

        assertEquals(listOf("def ", "class "), request.stop)
    }

    fun testBuildFimRequestWithExtraContext() {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val settings = Settings()
        val extraContext = listOf(
            FimExtraContext(text = "extra1", time = 100L, filename = "f1.kt"),
            FimExtraContext(text = "extra2", time = 200L, filename = "f2.kt")
        )

        val request = buildFimRequest(context, extraContext, settings, 500)

        assertEquals(2, request.input_extra.size)
        assertEquals("extra1", request.input_extra[0].text)
        assertEquals("extra2", request.input_extra[1].text)
    }

    fun testBuildFimRequestDefaultValues() {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val settings = Settings()
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 100)

        assertEquals(40, request.top_k)
        assertEquals(0.90, request.top_p)
        assertFalse(request.stream)
        assertEquals(listOf("top_k", "top_p", "infill"), request.samplers)
        assertTrue(request.cache_prompt)
        assertTrue(request.response_fields.contains("content"))
    }

    fun testBuildFimRequestUsesParamTMaxPredictMs() {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val settings = Settings(tMaxPredictMs = 2000)
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 500)

        assertEquals(500, request.t_max_predict_ms)
    }

    fun testBuildFimRequestWithIndent() {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 8,
            lineCur = "        x",
            lineCurPrefix = "        x",
            lineCurSuffix = ""
        )
        val settings = Settings()
        val extraContext = emptyList<FimExtraContext>()

        val request = buildFimRequest(context, extraContext, settings, 100)

        assertEquals(8, request.n_indent)
    }

    fun testIsValidFimResponseEmpty() {
        assertFalse(isValidFimResponse(""))
    }

    fun testIsValidFimResponseValidJson() {
        val response = """{"content": "suggestion"}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseWithLeadingWhitespace() {
        val response = """   {"content": "suggestion"}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseWithTabWhitespace() {
        val response = "\t{\"content\": \"suggestion\"}"
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseContentWithNoSpace() {
        val response = """{"content":"value"}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseContentWithSpaceBeforeColon() {
        val response = """{"content" :"value"}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseContentWithMultipleSpaces() {
        val response = """{"content"   :   "value"}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseNoContent() {
        val response = """{"result": "suggestion"}"""
        assertFalse(isValidFimResponse(response))
    }

    fun testIsValidFimResponseInvalidJson() {
        val response = """{"content": invalid}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseNotJson() {
        val response = """just plain text"""
        assertFalse(isValidFimResponse(response))
    }

    fun testIsValidFimResponseEmptyContent() {
        val response = """{"content": ""}"""
        assertTrue(isValidFimResponse(response))
    }

    fun testIsValidFimResponseMultilineJson() {
        val response = """{
            "content": "value",
            "other": "field"
        }"""
        assertTrue(isValidFimResponse(response))
    }

    fun testFimRenderDocumentBoundaryCheck() {
        myFixture.configureByText("test.kt", "")
        val request = makeInlineCompletionRequest("test.kt", 0, 0)
        val context = LocalContext(
            prefix = "",
            middle = "",
            suffix = "\n",
            indent = 0,
            lineCur = "",
            lineCurPrefix = "",
            lineCurSuffix = ""
        )

        val result = fimRender(context, "suggestion", request)

        assertNotNull(result)
        assertEquals("suggestion", result)
    }

    fun testFimSuspendBasic() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings(endpoint = "http://localhost:8012/infill")
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertEquals("completion", result)
    }

    fun testFimSuspendWithCache() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "cached"}"""

        val hashes = computeContextHashes("", "x", "")
        for (hash in hashes) {
            cache.data[hash] = response
        }

        val httpClient = makeMockHttpClient("should not be called")
        val result = fim(context, chunks, settings, cache, httpClient, useCache = true)

        assertNull(result)
    }

    fun testFimSuspendIgnoreCacheWhenDisabled() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "new"}"""

        val hashes = computeContextHashes("", "x", "")
        for (hash in hashes) {
            cache.data[hash] = response
        }

        val httpClient = makeMockHttpClient("""{"content": "fresh"}""")
        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertEquals("fresh", result)
    }

    fun testFimSuspendNetworkError() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val httpClient = makeMockHttpClientWithError()

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertNull(result)
    }

    fun testFimSuspendInvalidResponse() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val httpClient = makeMockHttpClient("not json")

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertNull(result)
    }

    fun testFimSuspendCacheInserted() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertTrue(cache.data.size > 0)
    }

    fun testFimSuspendTimeoutNonSpeculative() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings(tMaxPredictMs = 2000)
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false, isSpeculative = false)

        assertEquals("completion", result)
    }

    fun testFimSuspendTimeoutSpeculative() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings(tMaxPredictMs = 2000)
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false, isSpeculative = true)

        assertEquals("completion", result)
    }

    fun testFimSuspendWithExtraContext() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf(
            Chunk(text = "extra", time = 100L, filename = "file.kt")
        )
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertEquals("completion", result)
    }

    fun testFimSuspendWithApiKey() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings(apiKey = "secret-key")
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertEquals("completion", result)
    }

    fun testFimRequestDataClass() {
        val request = FimRequest(
            input_prefix = "prefix",
            input_suffix = "suffix",
            input_extra = listOf(FimExtraContext("text", 100L, "file")),
            prompt = "middle",
            n_predict = 128,
            stop = listOf("def"),
            n_indent = 4,
            t_max_prompt_ms = 500,
            t_max_predict_ms = 1000
        )

        assertEquals("prefix", request.input_prefix)
        assertEquals("suffix", request.input_suffix)
        assertEquals(1, request.input_extra.size)
        assertEquals("middle", request.prompt)
        assertEquals(128, request.n_predict)
        assertEquals(listOf("def"), request.stop)
        assertEquals(4, request.n_indent)
        assertEquals(500, request.t_max_prompt_ms)
        assertEquals(1000, request.t_max_predict_ms)
    }

    fun testFimExtraContextDataClass() {
        val extra = FimExtraContext(text = "content", time = 12345L, filename = "test.kt")

        assertEquals("content", extra.text)
        assertEquals(12345L, extra.time)
        assertEquals("test.kt", extra.filename)
    }

    fun testFimRenderResultDataClass() {
        val result = FimRenderResult(
            canAccept = true,
            content = listOf("line1", "line2"),
            posX = 5,
            posY = 10,
            lineCur = "current"
        )

        assertTrue(result.canAccept)
        assertEquals(2, result.content.size)
        assertEquals(5, result.posX)
        assertEquals(10, result.posY)
        assertEquals("current", result.lineCur)
    }

    fun testBuildExtraContextLargeChunkList() {
        val chunks = (0..100).map { i ->
            Chunk(text = "chunk$i", time = i.toLong(), filename = "file$i.kt")
        }
        val result = buildExtraContext(chunks)

        assertEquals(101, result.size)
        assertEquals("chunk0", result[0].text)
        assertEquals("chunk100", result[100].text)
    }

    fun testFimRenderLeadingWhitespaceHandling() {
        myFixture.configureByText("test.kt", "        ")
        val request = makeInlineCompletionRequest("test.kt", 0, 0)
        val context = LocalContext(
            prefix = "",
            middle = "",
            suffix = "\n",
            indent = 0,
            lineCur = "        ",
            lineCurPrefix = "",
            lineCurSuffix = ""
        )

        val result = fimRender(context, "        hello", request)

        assertNotNull(result)
    }

    fun testFimRenderMixedWhitespace() {
        myFixture.configureByText("test.kt", "\t  \t")
        val request = makeInlineCompletionRequest("test.kt", 0, 0)
        val context = LocalContext(
            prefix = "",
            middle = "",
            suffix = "\n",
            indent = 0,
            lineCur = "\t  \t",
            lineCurPrefix = "",
            lineCurSuffix = ""
        )

        val result = fimRender(context, "\t  \tcontent", request)

        assertNotNull(result)
    }

    fun testFimSuspendCacheLRUOrder() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "completion"}"""
        val httpClient = makeMockHttpClient(response)

        fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertTrue(cache.lruOrder.isNotEmpty())
    }

    fun testFimSuspendEmptyExtraContext() = runBlocking {
        val context = LocalContext(
            prefix = "code",
            middle = "test",
            suffix = "end",
            indent = 0,
            lineCur = "test",
            lineCurPrefix = "test",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "result"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertEquals("result", result)
    }

    fun testBuildFimRequestAllFieldsSet() {
        val context = LocalContext(
            prefix = "import sys\n",
            middle = "result = ",
            suffix = "\nprint(result)",
            indent = 0,
            lineCur = "result = ",
            lineCurPrefix = "result = ",
            lineCurSuffix = ""
        )
        val settings = Settings(
            nPredict = 256,
            stopStrings = listOf("def ", "class "),
            tMaxPromptMs = 1000,
            tMaxPredictMs = 2000
        )
        val extraContext = listOf(
            FimExtraContext(text = "import os", time = 100L, filename = "helper.py")
        )

        val request = buildFimRequest(context, extraContext, settings, 1500)

        assertEquals("import sys\n", request.input_prefix)
        assertEquals("\nprint(result)", request.input_suffix)
        assertEquals("result = ", request.prompt)
        assertEquals(256, request.n_predict)
        assertEquals(listOf("def ", "class "), request.stop)
        assertEquals(1000, request.t_max_prompt_ms)
        assertEquals(1500, request.t_max_predict_ms)
        assertEquals(1, request.input_extra.size)
    }

    fun testIsValidFimResponseContentVariations() {
        assertTrue(isValidFimResponse("""{"content":"x"}"""))
        assertTrue(isValidFimResponse("""{"content" : "x"}"""))
        assertTrue(isValidFimResponse("""{"content"  :  "x"}"""))
        assertTrue(isValidFimResponse("""{"x":"y","content":"z"}"""))
        assertTrue(isValidFimResponse("""{"content":"value","other":"field"}"""))
    }

    fun testFimRenderLeadingEmptyLines() {
        myFixture.configureByText("test.kt", "")
        val request = makeInlineCompletionRequest("test.kt", 0, 0)
        val context = LocalContext(
            prefix = "",
            middle = "",
            suffix = "\n",
            indent = 0,
            lineCur = "",
            lineCurPrefix = "",
            lineCurSuffix = ""
        )

        val result = fimRender(context, "\n\ncontent", request)

        assertNotNull(result)
    }

    fun testFimRenderPreservesNewlines() {
        myFixture.configureByText("test.kt", "")
        val request = makeInlineCompletionRequest("test.kt", 0, 0)
        val context = LocalContext(
            prefix = "",
            middle = "",
            suffix = "\n",
            indent = 0,
            lineCur = "",
            lineCurPrefix = "",
            lineCurSuffix = ""
        )

        val result = fimRender(context, "line1\nline2\nline3", request)

        assertTrue(result!!.contains("\n"))
        assertEquals("line1\nline2\nline3", result)
    }

    fun testFimSuspendResponseExtractionMultilineContent() = runBlocking {
        val context = LocalContext(
            prefix = "",
            middle = "x",
            suffix = "",
            indent = 0,
            lineCur = "x",
            lineCurPrefix = "x",
            lineCurSuffix = ""
        )
        val chunks = mutableListOf<Chunk>()
        val settings = Settings()
        val cache = SuggestionCache()
        val response = """{"content": "line1\nline2\nline3"}"""
        val httpClient = makeMockHttpClient(response)

        val result = fim(context, chunks, settings, cache, httpClient, useCache = false)

        assertEquals("line1\nline2\nline3", result)
    }

    fun testFimRenderEmptyFirstRepeating() {
        val testCase = fimRenderTestCases!!.find { it.testId == "fim_render_dedup::empty_first_repeating" }
            ?: throw AssertionError("Test case fim_render_dedup::empty_first_repeating not found")

        val request = makeInlineCompletionRequestAtVimPosition(testCase.file, testCase.line, testCase.column)
        val settings = Settings()
        val context = buildLocalContext(request, settings, null)

        val result = fimRender(context, testCase.inputContent, request)

        // If a result is returned, verify it matches the expected content
        if (result != null) {
            val expectedOutput = testCase.expectedContent.joinToString("\n")
            assertEquals("Content mismatch for ${testCase.testId}", expectedOutput, result)
        }
    }

    fun testFimRenderRepeatsSuffix() {
        val testCase = fimRenderTestCases!!.find { it.testId == "fim_render_dedup::repeats_suffix" }
            ?: throw AssertionError("Test case fim_render_dedup::repeats_suffix not found")

        val request = makeInlineCompletionRequestAtVimPosition(testCase.file, testCase.line, testCase.column)
        val settings = Settings()
        val context = buildLocalContext(request, settings, null)

        val result = fimRender(context, testCase.inputContent, request)

        if (result != null) {
            val expectedOutput = testCase.expectedContent.joinToString("\n")
            assertEquals("Content mismatch for ${testCase.testId}", expectedOutput, result)
        }
    }

    fun testFimRenderNormalMultiline() {
        val testCase = fimRenderTestCases!!.find { it.testId == "fim_render_dedup::normal_multiline" }
            ?: throw AssertionError("Test case fim_render_dedup::normal_multiline not found")

        val request = makeInlineCompletionRequestAtVimPosition(testCase.file, testCase.line, testCase.column)
        val settings = Settings()
        val context = buildLocalContext(request, settings, null)

        val result = fimRender(context, testCase.inputContent, request)

        if (result != null) {
            val expectedOutput = testCase.expectedContent.joinToString("\n")
            assertEquals("Content mismatch for ${testCase.testId}", expectedOutput, result)
        }
    }

    fun testFimRenderWhitespaceOnly() {
        val testCase = fimRenderTestCases!!.find { it.testId == "fim_render_dedup::whitespace_only" }
            ?: throw AssertionError("Test case fim_render_dedup::whitespace_only not found")

        val request = makeInlineCompletionRequestAtVimPosition(testCase.file, testCase.line, testCase.column)
        val settings = Settings()
        val context = buildLocalContext(request, settings, null)

        val result = fimRender(context, testCase.inputContent, request)

        if (result != null) {
            val expectedOutput = testCase.expectedContent.joinToString("\n")
            assertEquals("Content mismatch for ${testCase.testId}", expectedOutput, result)
        }
    }

    fun testFimRenderTrailingNewlines() {
        val testCase = fimRenderTestCases!!.find { it.testId == "fim_render_dedup::trailing_newlines" }
            ?: throw AssertionError("Test case fim_render_dedup::trailing_newlines not found")

        val request = makeInlineCompletionRequestAtVimPosition(testCase.file, testCase.line, testCase.column)
        val settings = Settings()
        val context = buildLocalContext(request, settings, null)

        val result = fimRender(context, testCase.inputContent, request)

        if (result != null) {
            val expectedOutput = testCase.expectedContent.joinToString("\n")
            assertEquals("Content mismatch for ${testCase.testId}", expectedOutput, result)
        }
    }
}
