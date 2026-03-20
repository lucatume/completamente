package com.github.lucatume.completamente.order89

import kotlinx.serialization.json.*

enum class ToolUsageMode { OFF, MANUAL, AUTO }

data class ToolCall(val name: String, val arguments: Map<String, JsonElement>)

data class ToolResult(val call: ToolCall, val output: String)

private val TOOL_CALL_REGEX = Regex("""<tool_call>\s*(\{.*?})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)

fun extractToolCalls(response: String): List<ToolCall> {
    return TOOL_CALL_REGEX.findAll(response).mapNotNull { match ->
        try {
            val json = Json.parseToJsonElement(match.groupValues[1]).jsonObject
            val name = json["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val arguments = json["arguments"]?.jsonObject?.toMap() ?: emptyMap()
            ToolCall(name, arguments)
        } catch (_: Exception) {
            null
        }
    }.toList()
}

private val TOOLS_PREFIX_REGEX = Regex("""^/tools(?:\s+|$)""", RegexOption.IGNORE_CASE)

fun parseToolsPrefix(prompt: String, mode: ToolUsageMode): Pair<String, Boolean> {
    return when (mode) {
        ToolUsageMode.OFF -> Pair(prompt, false)
        ToolUsageMode.MANUAL -> {
            val match = TOOLS_PREFIX_REGEX.find(prompt)
            if (match != null) {
                Pair(prompt.substring(match.range.last + 1).trimStart(), true)
            } else {
                Pair(prompt, false)
            }
        }
        ToolUsageMode.AUTO -> {
            val match = TOOLS_PREFIX_REGEX.find(prompt)
            if (match != null) {
                Pair(prompt.substring(match.range.last + 1).trimStart(), true)
            } else {
                Pair(prompt, true)
            }
        }
    }
}
