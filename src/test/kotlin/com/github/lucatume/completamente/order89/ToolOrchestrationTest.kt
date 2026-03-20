package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Settings

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
        return """{"content": ${kotlinx.serialization.json.JsonPrimitive(content)}}"""
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
        val statuses = mutableListOf<String>()
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
        assertTrue(statuses.any { it.contains("Gathering info") })
        assertTrue(statuses.any { it.contains("Searching") })
        assertTrue(statuses.any { it.contains("Generating code") })
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
}
