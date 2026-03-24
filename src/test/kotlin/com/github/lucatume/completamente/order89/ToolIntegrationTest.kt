package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Settings
import kotlinx.serialization.json.JsonPrimitive

class ToolIntegrationTest : BaseCompletionTest() {

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

    fun testOffModeToolsPrefixTreatedAsLiteral() {
        val (prompt, enabled) = parseToolsPrefix("/tools search for foo", ToolUsageMode.OFF)
        assertEquals("/tools search for foo", prompt)
        assertFalse(enabled)
        // Build prompt should contain the literal /tools text
        val request = makeRequest(prompt = prompt)
        val builtPrompt = Order89Executor.buildPrompt(request)
        assertTrue(builtPrompt.contains("/tools search for foo"))
    }

    fun testOffModeBuildPromptUsed() {
        val request = makeRequest()
        val prompt = Order89Executor.buildPrompt(request)
        assertTrue(prompt.contains("<Order89Prompt>"))
        assertFalse(prompt.contains("<|im_start|>"))
    }

    fun testManualModeWithToolsPrefix() {
        val (prompt, enabled) = parseToolsPrefix("/tools search for foo", ToolUsageMode.MANUAL)
        assertTrue(enabled)
        assertEquals("search for foo", prompt)
        val request = makeRequest(prompt = prompt)
        val chatPrompt = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue(chatPrompt.contains("<|im_start|>"))
        assertTrue(chatPrompt.contains("FileSearch"))
    }

    fun testManualModeWithoutToolsPrefix() {
        val (prompt, enabled) = parseToolsPrefix("search for foo", ToolUsageMode.MANUAL)
        assertFalse(enabled)
        assertEquals("search for foo", prompt)
        // Without tools, use the existing buildPrompt
        val request = makeRequest(prompt = prompt)
        val builtPrompt = Order89Executor.buildPrompt(request)
        assertTrue(builtPrompt.contains("<Order89Prompt>"))
        assertFalse(builtPrompt.contains("<|im_start|>"))
    }

    fun testAutoModeAlwaysChatPromptWithToolSpec() {
        val (prompt, enabled) = parseToolsPrefix("search for foo", ToolUsageMode.AUTO)
        assertTrue(enabled)
        val request = makeRequest(prompt = prompt)
        val chatPrompt = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue(chatPrompt.contains("<|im_start|>"))
        assertTrue(chatPrompt.contains("FileSearch"))
        assertTrue(chatPrompt.contains("DocSearch"))
        assertFalse(chatPrompt.contains("WebSearch"))
    }

    fun testFullPipelinePhase1ToolCallPhase2Code() {
        val settings = Settings(order89MaxToolRounds = 3)
        val request = makeRequest(prompt = "use the gateway")
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, settings,
            toolExecutor = { call ->
                assertEquals("FileSearch", call.name)
                "src/Gateway.kt:5: fun process()"
            },
            completionFn = { _, _ ->
                callCount++
                if (callCount == 1) {
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"Gateway\"}}\n</tool_call>")
                } else {
                    fakeResponse("```kotlin\nGateway.process()\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals("Gateway.process()", result.output)
    }

    fun testPhase1DirectCodeNoToolCalls() {
        val settings = Settings(order89MaxToolRounds = 3)
        val request = makeRequest(prompt = "return 1")
        val result = Order89Executor.executeWithTools(
            request, settings,
            toolExecutor = { error("Should not call tools") },
            completionFn = { _, _ -> fakeResponse("```kotlin\nreturn 1\n```") }
        )
        assertTrue(result.success)
        assertEquals("return 1", result.output)
    }

    fun testToolResultsAppearAsReferenceCodeInPhase2() {
        val toolResults = listOf(
            ToolResult(
                ToolCall("FileSearch", mapOf("query" to JsonPrimitive("processPayment"))),
                "src/Pay.kt:10: fun processPayment()"
            )
        )
        val request = makeRequest()
        val phase2Prompt = Order89Executor.buildChatPrompt(request, toolResults, includeTools = false)
        assertTrue(phase2Prompt.contains("<ReferenceCode source=\"FileSearch: processPayment\">"))
        assertTrue(phase2Prompt.contains("src/Pay.kt:10: fun processPayment()"))
        assertFalse(phase2Prompt.contains("To use a tool, respond with:"))
    }

    fun testMaxRoundsExhaustedPhase2StillRuns() {
        val settings = Settings(order89MaxToolRounds = 3)
        val request = makeRequest()
        var callCount = 0
        val result = Order89Executor.executeWithTools(
            request, settings,
            toolExecutor = { "result" },
            completionFn = { _, _ ->
                callCount++
                if (callCount <= 3) {
                    fakeResponse("<tool_call>\n{\"name\": \"FileSearch\", \"arguments\": {\"query\": \"round$callCount\"}}\n</tool_call>")
                } else {
                    fakeResponse("```kotlin\nval done = true\n```")
                }
            }
        )
        assertTrue(result.success)
        assertEquals("val done = true", result.output)
        assertEquals(4, callCount) // 3 tool rounds + 1 Phase 2
    }

    fun testCleanOutputWorksOnChatTemplateResponses() {
        val rawOutput = "Here's the code:\n\n```kotlin\nfun hello() {\n    println(\"world\")\n}\n```\n\nThis should work."
        val cleaned = Order89Executor.cleanOutput(rawOutput)
        assertEquals("fun hello() {\n    println(\"world\")\n}", cleaned)
    }
}
