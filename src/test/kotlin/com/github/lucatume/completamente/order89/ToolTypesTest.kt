package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolTypesTest : BaseCompletionTest() {

    // -- extractToolCalls tests --

    fun testExtractToolCallsSingleCall() {
        val response = """<tool_call>
{"name": "FileSearch", "arguments": {"query": "processPayment"}}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("FileSearch", calls[0].name)
        assertEquals(JsonPrimitive("processPayment"), calls[0].arguments["query"])
    }

    fun testExtractToolCallsMultipleCalls() {
        val response = """<tool_call>
{"name": "FileSearch", "arguments": {"query": "foo"}}
</tool_call>
<tool_call>
{"name": "WebSearch", "arguments": {"query": "bar"}}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertEquals(2, calls.size)
        assertEquals("FileSearch", calls[0].name)
        assertEquals("WebSearch", calls[1].name)
    }

    fun testExtractToolCallsNoCalls() {
        val response = "Here is the code:\n```kotlin\nval x = 1\n```"
        val calls = extractToolCalls(response)
        assertTrue(calls.isEmpty())
    }

    fun testExtractToolCallsMalformedJson() {
        val response = "<tool_call>\n{not valid json}\n</tool_call>"
        val calls = extractToolCalls(response)
        assertTrue(calls.isEmpty())
    }

    fun testExtractToolCallsEmptyString() {
        assertTrue(extractToolCalls("").isEmpty())
    }

    fun testExtractToolCallsWhitespaceInTags() {
        val response = "<tool_call>   {\"name\": \"FileSearch\", \"arguments\": {\"query\": \"x\"}}   </tool_call>"
        val calls = extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("FileSearch", calls[0].name)
    }

    fun testExtractToolCallsMixedCodeAndToolCalls() {
        val response = """I need to search first.
<tool_call>
{"name": "FileSearch", "arguments": {"query": "test"}}
</tool_call>
And also:
<tool_call>
{"name": "FileSearch", "arguments": {"query": "other"}}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertEquals(2, calls.size)
    }

    fun testExtractToolCallsOptionalArguments() {
        val response = """<tool_call>
{"name": "FileSearch", "arguments": {"query": "test", "case_sensitive": true, "path": "src/"}}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals(JsonPrimitive(true), calls[0].arguments["case_sensitive"])
        assertEquals(JsonPrimitive("src/"), calls[0].arguments["path"])
    }

    fun testExtractToolCallsWebSearch() {
        val response = """<tool_call>
{"name": "WebSearch", "arguments": {"query": "kotlin coroutines tutorial"}}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("WebSearch", calls[0].name)
        assertEquals(JsonPrimitive("kotlin coroutines tutorial"), calls[0].arguments["query"])
    }

    fun testExtractToolCallsMissingName() {
        val response = """<tool_call>
{"arguments": {"query": "test"}}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertTrue(calls.isEmpty())
    }

    fun testExtractToolCallsNoArguments() {
        val response = """<tool_call>
{"name": "FileSearch"}
</tool_call>"""
        val calls = extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("FileSearch", calls[0].name)
        assertTrue(calls[0].arguments.isEmpty())
    }

    // -- parseToolsPrefix tests --

    fun testParseToolsPrefixOffPreservesLiteral() {
        val (prompt, enabled) = parseToolsPrefix("/tools search for foo", ToolUsageMode.OFF)
        assertEquals("/tools search for foo", prompt)
        assertFalse(enabled)
    }

    fun testParseToolsPrefixManualStripsPrefix() {
        val (prompt, enabled) = parseToolsPrefix("/tools search for foo", ToolUsageMode.MANUAL)
        assertEquals("search for foo", prompt)
        assertTrue(enabled)
    }

    fun testParseToolsPrefixManualNoPrefix() {
        val (prompt, enabled) = parseToolsPrefix("search for foo", ToolUsageMode.MANUAL)
        assertEquals("search for foo", prompt)
        assertFalse(enabled)
    }

    fun testParseToolsPrefixManualCaseInsensitive() {
        val (prompt, enabled) = parseToolsPrefix("/Tools search for foo", ToolUsageMode.MANUAL)
        assertEquals("search for foo", prompt)
        assertTrue(enabled)
    }

    fun testParseToolsPrefixManualUpperCase() {
        val (prompt, enabled) = parseToolsPrefix("/TOOLS search for foo", ToolUsageMode.MANUAL)
        assertEquals("search for foo", prompt)
        assertTrue(enabled)
    }

    fun testParseToolsPrefixToolsAlone() {
        val (prompt, enabled) = parseToolsPrefix("/tools", ToolUsageMode.MANUAL)
        assertEquals("", prompt)
        assertTrue(enabled)
    }

    fun testParseToolsPrefixAutoAlwaysTrue() {
        val (prompt, enabled) = parseToolsPrefix("search for foo", ToolUsageMode.AUTO)
        assertEquals("search for foo", prompt)
        assertTrue(enabled)
    }

    fun testParseToolsPrefixAutoStripsPrefix() {
        val (prompt, enabled) = parseToolsPrefix("/tools search for foo", ToolUsageMode.AUTO)
        assertEquals("search for foo", prompt)
        assertTrue(enabled)
    }

    fun testParseToolsPrefixManualMidPromptNotStripped() {
        val (prompt, enabled) = parseToolsPrefix("use /tools to search", ToolUsageMode.MANUAL)
        assertEquals("use /tools to search", prompt)
        assertFalse(enabled)
    }

    // -- formatToolCallDisplay tests --

    fun testFormatToolCallDisplaySingleArg() {
        val call = ToolCall("FileSearch", mapOf("query" to JsonPrimitive("TODO")))
        assertEquals("FileSearch( query: \"TODO\")", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayMultipleArgs() {
        val call = ToolCall("FileSearch", mapOf(
            "query" to JsonPrimitive("TODO"),
            "path" to JsonPrimitive("src/")
        ))
        assertEquals("FileSearch( query: \"TODO\", path: \"src/\")", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayNoArgs() {
        val call = ToolCall("FileSearch", emptyMap())
        assertEquals("FileSearch( )", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayWebSearch() {
        val call = ToolCall("WebSearch", mapOf("query" to JsonPrimitive("kotlin sealed class")))
        assertEquals("WebSearch( query: \"kotlin sealed class\")", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayNonPrimitiveObjectArg() {
        val objArg = buildJsonObject { put("type", "kt") }
        val call = ToolCall("FileSearch", mapOf("filters" to objArg))
        assertEquals("FileSearch( filters: {\"type\":\"kt\"})", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayNonPrimitiveArrayArg() {
        val arrArg = buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }
        val call = ToolCall("FileSearch", mapOf("ids" to arrArg))
        assertEquals("FileSearch( ids: [1,2])", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayNumericArg() {
        val call = ToolCall("FileSearch", mapOf("limit" to JsonPrimitive(42)))
        assertEquals("FileSearch( limit: 42)", formatToolCallDisplay(call))
    }

    fun testFormatToolCallDisplayBooleanArg() {
        val call = ToolCall("FileSearch", mapOf(
            "query" to JsonPrimitive("TODO"),
            "case_sensitive" to JsonPrimitive(true)
        ))
        assertEquals("FileSearch( query: \"TODO\", case_sensitive: true)", formatToolCallDisplay(call))
    }
}
