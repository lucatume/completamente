package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Settings
import kotlinx.serialization.json.JsonPrimitive

class ToolOrchestrationTest : BaseCompletionTest() {

    private val defaultSettings = Settings(order89MaxToolRounds = 3)

    private fun makeRequest(
        prompt: String = "add a method",
        fileContent: String = "class Foo {}",
        selectionStart: Int = 10,
        selectionEnd: Int = 10
    ) = Order89Request(
        prompt = prompt,
        filePath = "/test.kt",
        fileContent = fileContent,
        language = "kotlin",
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        contextChunks = emptyList()
    )

    private fun fakeResponse(content: String): String {
        return """{"content": ${JsonPrimitive(content)}}"""
    }

    fun testNoToolCallsReturnsCodeDirectly() {
        val request = makeRequest()
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { error("Should not be called") },
            completionFn = { _, _ -> fakeResponse("```kotlin\nfun foo() {}\n```") }
        )
        assertTrue(result.success)
        assertEquals("fun foo() {}", result.output)
    }

    fun testSingleRoundOneToolCallThenPhase2() {
        val request = makeRequest()
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { call ->
                assertEquals("FileSearch", call.name)
                "src/Foo.kt:1: class Foo"
            },
            completionFn = { body, _ ->
                callCount++
                if (callCount == 1) {
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"Foo\"}}\n</tool_call>")
                } else {
                    fakeResponse("```kotlin\nfun bar() {}\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals("fun bar() {}", result.output)
        assertEquals(2, callCount) // Phase 1 + Phase 2
    }

    fun testSingleRoundMultipleParallelCalls() {
        val request = makeRequest()
        var callCount = 0
        val toolNames = mutableListOf<String>()
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { call ->
                synchronized(toolNames) { toolNames.add(call.name) }
                "result for ${call.name}"
            },
            completionFn = { _, _ ->
                callCount++
                if (callCount == 1) {
                    fakeResponse(
                        "<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"a\"}}\n</tool_call>\n" +
                        "<tool_call>\n{\"name\": \"WebSearch\", \"arguments\": {\"query\": \"b\"}}\n</tool_call>"
                    )
                } else {
                    fakeResponse("```kotlin\nval x = 1\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals(2, callCount)
        assertEquals(2, toolNames.size)
        assertTrue(toolNames.contains("FileSearch"))
        assertTrue(toolNames.contains("WebSearch"))
    }

    fun testMultipleRoundsOfToolCalls() {
        val request = makeRequest()
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { "result" },
            completionFn = { _, _ ->
                callCount++
                if (callCount <= 2) {
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"round$callCount\"}}\n</tool_call>")
                } else {
                    fakeResponse("```kotlin\nval done = true\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals(3, callCount) // 2 tool rounds + Phase 2
    }

    fun testMaxRoundsEnforcedThenPhase2() {
        val settings = Settings(order89MaxToolRounds = 2)
        val request = makeRequest()
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, settings,
            toolExecutor = { "result" },
            completionFn = { _, _ ->
                callCount++
                if (callCount <= 2) {
                    // Always return tool calls — should be capped at 2 rounds
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"round$callCount\"}}\n</tool_call>")
                } else {
                    // Phase 2
                    fakeResponse("```kotlin\nval capped = true\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals("val capped = true", result.output)
        assertEquals(3, callCount) // 2 tool rounds + 1 Phase 2
    }

    fun testStatusUpdateCallbacks() {
        // Use maxRounds=1 so tool calls exhaust the limit and Phase 2 is triggered
        val settings = Settings(order89MaxToolRounds = 1)
        val request = makeRequest()
        val statuses = mutableListOf<StatusUpdate>()
        var callCount = 0
        Order89Executor.executeWithTools(
            request, settings,
            toolExecutor = { "result" },
            completionFn = { _, _ ->
                callCount++
                if (callCount == 1) {
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"test\"}}\n</tool_call>")
                } else {
                    fakeResponse("```kotlin\nval x = 1\n```")
                }
            },
            onStatusUpdate = { statuses.add(it) }
        )
        // First update: WaitingForModel (gathering info)
        assertTrue(statuses.any { it is StatusUpdate.WaitingForModel })
        // Second update: ToolCalls with the FileSearch call
        assertTrue(statuses.any { it is StatusUpdate.ToolCalls })
        val toolCallUpdate = statuses.filterIsInstance<StatusUpdate.ToolCalls>().first()
        assertEquals("FileSearch", toolCallUpdate.calls[0].name)
    }

    fun testPhase1ProducesCodeDirectlyNoPhase2() {
        val request = makeRequest()
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { error("Should not be called") },
            completionFn = { _, _ ->
                callCount++
                fakeResponse("```kotlin\nfun direct() {}\n```")
            }
        )
        assertTrue(result.success)
        assertEquals("fun direct() {}", result.output)
        assertEquals(1, callCount) // Only Phase 1, no Phase 2
    }

    fun testHttpErrorInPhase1ReturnsError() {
        val request = makeRequest()
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { error("Should not be called") },
            completionFn = { _, _ -> throw RuntimeException("Connection refused") }
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("Connection refused"))
    }

    fun testToolExecutorThrowsGracefulErrorHandling() {
        val request = makeRequest()
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, defaultSettings,
            toolExecutor = { throw RuntimeException("Tool failed") },
            completionFn = { _, _ ->
                callCount++
                if (callCount == 1) {
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"test\"}}\n</tool_call>")
                } else {
                    fakeResponse("```kotlin\nval fallback = true\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals("val fallback = true", result.output)
    }

    // -- buildChatPrompt Phase 2 tests --

    fun testBuildChatPromptPhase2InjectsReferenceCodeBlocks() {
        val request = makeRequest()
        val toolResults = listOf(
            ToolResult(
                ToolCall("FileSearch", mapOf("query" to JsonPrimitive("processPayment"))),
                "src/Payment.kt:42: fun processPayment()"
            )
        )
        val prompt = Order89Executor.buildChatPrompt(request, toolResults, includeTools = false)
        assertTrue("Should contain ReferenceCode block", prompt.contains("<ReferenceCode source="))
        assertTrue("Should contain tool output", prompt.contains("src/Payment.kt:42: fun processPayment()"))
        assertTrue("Should contain source attribution", prompt.contains("FileSearch"))
        assertFalse("Should not contain tool spec", prompt.contains("To use a tool, respond with"))
    }

    fun testBuildChatPromptPhase2EscapesSourceAttribute() {
        val request = makeRequest()
        val toolResults = listOf(
            ToolResult(
                ToolCall("FileSearch", mapOf("query" to JsonPrimitive("foo<bar>\"baz"))),
                "result"
            )
        )
        val prompt = Order89Executor.buildChatPrompt(request, toolResults, includeTools = false)
        assertTrue("Should escape angle brackets", prompt.contains("&lt;"))
        assertTrue("Should escape quotes", prompt.contains("&quot;"))
        assertFalse("Should not contain raw angle bracket in source", prompt.contains("source=\"FileSearch: foo<bar>"))
    }

    fun testBuildChatPromptPhase1WithToolResultsDoesNotInjectReferenceCode() {
        val request = makeRequest()
        val toolResults = listOf(
            ToolResult(
                ToolCall("FileSearch", mapOf("query" to JsonPrimitive("foo"))),
                "some result"
            )
        )
        val prompt = Order89Executor.buildChatPrompt(request, toolResults, includeTools = true)
        assertFalse("Phase 1 re-prompt should not contain ReferenceCode blocks", prompt.contains("<ReferenceCode"))
        assertTrue("Phase 1 should still include tool spec", prompt.contains("To use a tool, respond with"))
    }

    fun testBuildChatPromptPhase1IncludesToolSpec() {
        val request = makeRequest()
        val prompt = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue("Should contain tool spec", prompt.contains("To use a tool, respond with"))
        assertTrue("Should contain FileSearch", prompt.contains("FileSearch"))
        assertFalse("Should not contain ReferenceCode", prompt.contains("<ReferenceCode"))
    }

    // -- escapeXmlAttr tests --

    fun testEscapeXmlAttrPlainText() {
        assertEquals("hello world", escapeXmlAttr("hello world"))
    }

    fun testEscapeXmlAttrEmptyString() {
        assertEquals("", escapeXmlAttr(""))
    }

    fun testEscapeXmlAttrEscapesAllSpecialChars() {
        assertEquals("a&amp;b&quot;c&apos;d&lt;e&gt;f", escapeXmlAttr("a&b\"c'd<e>f"))
    }

    fun testEscapeXmlAttrDoesNotDoubleEscape() {
        assertEquals("&amp;amp;", escapeXmlAttr("&amp;"))
    }

    // -- buildPrompt escaping --

    fun testBuildPromptEscapesChunkPath() {
        val request = Order89Request(
            prompt = "do something",
            filePath = "/test.kt",
            fileContent = "class Foo {}",
            language = "kotlin",
            selectionStart = 10,
            selectionEnd = 10,
            contextChunks = listOf(ContextChunk("path/with\"quotes>&special", "content"))
        )
        val prompt = Order89Executor.buildPrompt(request)
        assertTrue("Should escape quotes in path", prompt.contains("&quot;"))
        assertTrue("Should escape angle brackets in path", prompt.contains("&gt;"))
        assertFalse("Should not contain raw special chars in path attr", prompt.contains("path=\"path/with\"quotes>"))
    }
}
