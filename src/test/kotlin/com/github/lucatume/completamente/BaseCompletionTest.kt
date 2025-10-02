package com.github.lucatume.completamente

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseCompletionTest : BasePlatformTestCase() {
    private val testDataPath = "src/test/testData/completion/fim_requests_corpus.json"
    override fun getTestDataPath() = "src/test/testData/completion"

    fun makeInlineCompletionEvent(): InlineCompletionEvent {
        return object : InlineCompletionEvent {
            override fun toRequest(): InlineCompletionRequest {
                throw UnsupportedOperationException("event.toRequest() is not supported in tests")
            }
        }
    }

    fun makeInlineCompletionRequest(filePath: String, startOffset: Int, endOffset: Int): InlineCompletionRequest {
        val event = makeInlineCompletionEvent()
        val file = myFixture.configureByFile(filePath)
        val editor = myFixture.editor
        val document = myFixture.getDocument(file)

        return InlineCompletionRequest(event, file, editor, document, startOffset, endOffset)
    }

    fun makeMockHttpClient(response: String): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    fun makeMockHttpClientWithTracking(response: String, requestTracker: AtomicInteger): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestTracker.incrementAndGet()
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    fun makeMockHttpClientWithError(): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    throw RuntimeException("Network error")
                }
            }
        }
    }

    fun makeMockHttpClientWithDelay(
        response: String,
        delayMs: Long,
        requestTracker: AtomicInteger
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestTracker.incrementAndGet()
                    kotlinx.coroutines.delay(delayMs)
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    fun makeInlineCompletionRequestAtVimPosition(
        filePath: String,
        line: Int,  // vim line number (1-based)
        column: Int  // vim column position (0-based)
    ): InlineCompletionRequest {
        val file = myFixture.configureByFile(filePath)
        val editor = myFixture.editor
        val document = myFixture.getDocument(file)

        // vim uses 1-based line numbers, convert to 0-based
        val lineIndex = line - 1
        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)

        // Clamp column to be within the line (but not including the newline)
        val lineLength = lineEndOffset - lineStartOffset
        val clampedColumn = minOf(column, lineLength)

        // The IDE sends startOffset as the previous position and endOffset as the position to complete for.
        val offset = lineStartOffset + clampedColumn
        val startOffset = maxOf(0, offset -1)

        val event = makeInlineCompletionEvent()
        return InlineCompletionRequest(event, file, editor, document, startOffset, offset)
    }

    protected fun loadTestData(): List<TestCase> {
        val file = File(testDataPath)
        if (!file.exists()) {
            throw IllegalStateException("Test data file not found: $testDataPath")
        }

        val json = Json { ignoreUnknownKeys = true }
        val parsedElement = json.parseToJsonElement(file.readText())
        val results = when {
            parsedElement is JsonArray -> parsedElement
            parsedElement is JsonObject -> {
                parsedElement["results"] as? JsonArray
                    ?: throw IllegalStateException("No results array found in JSON object")
            }
            else -> throw IllegalStateException("Invalid JSON format")
        }

        return results.mapNotNull { element ->
            val obj = element.jsonObject
            val request = obj["request"]?.jsonObject ?: return@mapNotNull null

            TestCase(
                testId = obj["test_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                file = obj["file"]?.jsonPrimitive?.content?.replace("ref/bin/testdata/", "") ?: return@mapNotNull null,
                line = obj["line"]?.jsonPrimitive?.content?.toInt() ?: return@mapNotNull null,
                column = obj["column"]?.jsonPrimitive?.content?.toInt() ?: return@mapNotNull null,
                expectedPrefix = request["input_prefix"]?.jsonPrimitive?.content ?: "",
                expectedMiddle = request["prompt"]?.jsonPrimitive?.content ?: "",
                expectedSuffix = request["input_suffix"]?.jsonPrimitive?.content ?: "",
                expectedIndent = request["n_indent"]?.jsonPrimitive?.content?.toInt() ?: 0
            )
        }
    }

    data class TestCase(
        val testId: String,
        val file: String,
        val line: Int,
        val column: Int,
        val expectedPrefix: String,
        val expectedMiddle: String,
        val expectedSuffix: String,
        val expectedIndent: Int
    )

    protected fun loadFimRenderTestData(): List<FimRenderTestCase> {
        val file = File(testDataPath)
        if (!file.exists()) {
            throw IllegalStateException("Test data file not found: $testDataPath")
        }

        val json = Json { ignoreUnknownKeys = true }
        val parsedElement = json.parseToJsonElement(file.readText())
        val results = when {
            parsedElement is JsonArray -> parsedElement
            parsedElement is JsonObject -> {
                parsedElement["results"] as? JsonArray
                    ?: throw IllegalStateException("No results array found in JSON object")
            }
            else -> throw IllegalStateException("Invalid JSON format")
        }

        return results.mapNotNull { element ->
            val obj = element.jsonObject
            val testId = obj["test_id"]?.jsonPrimitive?.content ?: return@mapNotNull null

            // Filter for fim_render_dedup test cases only
            if (!testId.startsWith("fim_render_dedup::")) {
                return@mapNotNull null
            }

            val fimRender = obj["fim_render"]?.jsonObject ?: return@mapNotNull null
            val contentArray = fimRender["content"]?.jsonArray ?: return@mapNotNull null

            FimRenderTestCase(
                testId = testId,
                file = obj["file"]?.jsonPrimitive?.content?.substringAfterLast("/") ?: return@mapNotNull null,
                line = obj["line"]?.jsonPrimitive?.content?.toInt() ?: return@mapNotNull null,
                column = obj["column"]?.jsonPrimitive?.content?.toInt() ?: return@mapNotNull null,
                inputContent = obj["input_content"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                expectedPosX = fimRender["pos_x"]?.jsonPrimitive?.content?.toInt() ?: return@mapNotNull null,
                expectedPosY = fimRender["pos_y"]?.jsonPrimitive?.content?.toInt() ?: return@mapNotNull null,
                expectedLineCur = fimRender["line_cur"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                expectedCanAccept = fimRender["can_accept"]?.jsonPrimitive?.content?.toBoolean() ?: return@mapNotNull null,
                expectedContent = contentArray.map { it.jsonPrimitive.content }
            )
        }
    }

    data class FimRenderTestCase(
        val testId: String,
        val file: String,
        val line: Int,
        val column: Int,
        val inputContent: String,
        val expectedPosX: Int,
        val expectedPosY: Int,
        val expectedLineCur: String,
        val expectedCanAccept: Boolean,
        val expectedContent: List<String>
    )
}
