package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.services.Settings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.serialization.json.*

data class ContextChunk(
    val path: String,
    val content: String
)

data class Order89Request(
    val prompt: String,
    val filePath: String,
    val fileContent: String,
    val language: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val contextChunks: List<ContextChunk>
)

data class Order89Result(
    val success: Boolean,
    val output: String
)

internal fun escapeXmlAttr(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&apos;").replace("<", "&lt;").replace(">", "&gt;")

object Order89Executor {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun buildPrompt(request: Order89Request): String {
        val before = request.fileContent.substring(0, request.selectionStart)
        val selection = request.fileContent.substring(request.selectionStart, request.selectionEnd)
        val after = request.fileContent.substring(request.selectionEnd)

        return buildString {
            appendLine("<Order89Prompt>")
            appendLine("You are a code transformation tool. You receive a file with a marked selection and an instruction.")
            appendLine("You output ONLY the code that replaces the selection.")
            appendLine()
            appendLine("<Order89Rules>")
            appendLine("- Wrap your code output in a fenced code block using triple backticks with the language identifier.")
            appendLine("- Do NOT add documentation blocks, comments, or type annotations that the surrounding")
            appendLine("  code does not already use. Conversely, if the surrounding code includes documentation")
            appendLine("  blocks on every function, include one on yours in the same format.")
            appendLine("- Preserve the indentation style, brace placement, and whitespace patterns of the")
            appendLine("  surrounding code.")
            appendLine("- Do NOT describe what you are about to do. Do NOT explain your reasoning.")
            appendLine("- Do NOT include any text before or after the fenced code block.")
            appendLine("- If the selection is empty (<Order89UserSelection></Order89UserSelection>),")
            appendLine("  output code to insert at that position.")
            appendLine("</Order89Rules>")
            appendLine()
            appendLine("<Order89Context>")
            appendLine("The following files are referenced by the file under edit. Use them to understand")
            appendLine("the APIs and types available, so your generated code calls real methods with correct signatures.")
            for (chunk in request.contextChunks) {
                appendLine()
                appendLine("<Order89ContextFile path=\"${escapeXmlAttr(chunk.path)}\">")
                appendLine(chunk.content)
                appendLine("</Order89ContextFile>")
            }
            appendLine("</Order89Context>")
            appendLine()
            appendLine("Language: ${request.language}")
            appendLine("File: ${request.filePath}")
            appendLine()
            appendLine("<Order89Instruction>")
            appendLine(request.prompt)
            appendLine("</Order89Instruction>")
            appendLine()
            appendLine("REMINDER: Match the file's documentation style.")
            appendLine()
            appendLine("<Order89FileContent>")
            append(before)
            append("<Order89UserSelection>")
            append(selection)
            append("</Order89UserSelection>")
            append(after)
            appendLine()
            appendLine("</Order89FileContent>")
            appendLine("</Order89Prompt>")
        }
    }

    private const val TOOL_SPEC = """You have two tools you may call to gather information before writing code:

1. FileSearch — Finds files in the project containing a string. Returns file:line pairs.
   Case-insensitive by default.
   Parameters: query (required, string), case_sensitive (optional, boolean, default false),
   path (optional, string, file or directory to search recursively)

2. WebSearch — Searches the web.
   Parameters: query (required, string)

To use a tool, respond with:
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>

When you have gathered the information you need, produce your code output as specified in the rules."""

    private const val TOOL_CALLING_RULE = "- If you need information from project files or the web to correctly implement the instruction,\n" +
        "  call the appropriate tool FIRST. Once you have the information, produce the code."

    fun buildChatPrompt(
        request: Order89Request,
        toolResults: List<ToolResult> = emptyList(),
        includeTools: Boolean = true
    ): String {
        return buildString {
            // System message
            append("<|im_start|>system\n")
            appendLine("You are a code transformation tool. You receive a file with a marked selection and an instruction.")
            appendLine("You output ONLY the code that replaces the selection.")
            appendLine()
            appendLine("<Order89Rules>")
            appendLine("- Wrap your code output in a fenced code block using triple backticks with the language identifier.")
            appendLine("- Do NOT add documentation blocks, comments, or type annotations that the surrounding")
            appendLine("  code does not already use. Conversely, if the surrounding code includes documentation")
            appendLine("  blocks on every function, include one on yours in the same format.")
            appendLine("- Preserve the indentation style, brace placement, and whitespace patterns of the")
            appendLine("  surrounding code.")
            appendLine("- Do NOT describe what you are about to do. Do NOT explain your reasoning.")
            appendLine("- Do NOT include any text before or after the fenced code block.")
            appendLine("- If the selection is empty (<Order89UserSelection></Order89UserSelection>),")
            appendLine("  output code to insert at that position.")
            if (includeTools) {
                appendLine(TOOL_CALLING_RULE)
            }
            appendLine("</Order89Rules>")
            if (includeTools) {
                appendLine()
                appendLine(TOOL_SPEC)
            }
            append("<|im_end|>\n")

            // User message
            append("<|im_start|>user\n")

            // Inject tool results as ReferenceCode blocks (Phase 2)
            if (!includeTools && toolResults.isNotEmpty()) {
                for (result in toolResults) {
                    val rawSource = "${result.call.name}: ${result.call.arguments["query"]?.let {
                        it.jsonPrimitive.content
                    } ?: ""}"
                    val source = escapeXmlAttr(rawSource)
                    appendLine("<ReferenceCode source=\"$source\">")
                    appendLine(result.output)
                    appendLine("</ReferenceCode>")
                    appendLine()
                }
            }

            appendLine("<Order89Context>")
            appendLine("The following files are referenced by the file under edit. Use them to understand")
            appendLine("the APIs and types available, so your generated code calls real methods with correct signatures.")
            for (chunk in request.contextChunks) {
                appendLine()
                appendLine("<Order89ContextFile path=\"${escapeXmlAttr(chunk.path)}\">")
                appendLine(chunk.content)
                appendLine("</Order89ContextFile>")
            }
            appendLine("</Order89Context>")
            appendLine()
            appendLine("Language: ${request.language}")
            appendLine("File: ${request.filePath}")
            appendLine()
            appendLine("<Order89Instruction>")
            appendLine(request.prompt)
            appendLine("</Order89Instruction>")
            appendLine()
            appendLine("REMINDER: Match the file's documentation style.")
            appendLine()
            appendLine("<Order89FileContent>")
            val before = request.fileContent.substring(0, request.selectionStart)
            val selection = request.fileContent.substring(request.selectionStart, request.selectionEnd)
            val after = request.fileContent.substring(request.selectionEnd)
            append(before)
            append("<Order89UserSelection>")
            append(selection)
            append("</Order89UserSelection>")
            append(after)
            appendLine()
            appendLine("</Order89FileContent>")
            append("<|im_end|>\n")

            // Assistant turn
            append("<|im_start|>assistant\n")
        }
    }

    fun buildChatRequestBody(prompt: String, settings: Settings): String {
        val json = buildJsonObject {
            put("prompt", prompt)
            put("n_predict", settings.order89NPredict)
            put("temperature", settings.order89Temperature)
            put("top_p", settings.order89TopP)
            put("top_k", settings.order89TopK)
            put("repeat_penalty", settings.order89RepeatPenalty)
            putJsonArray("stop") {
                add("<|im_end|>")
                add("<|im_start|>")
            }
            put("cache_prompt", false)
        }
        return json.toString()
    }

    fun buildRequestBody(prompt: String, settings: Settings): String {
        val json = buildJsonObject {
            put("prompt", prompt)
            put("n_predict", settings.order89NPredict)
            put("temperature", settings.order89Temperature)
            put("top_p", settings.order89TopP)
            put("top_k", settings.order89TopK)
            put("repeat_penalty", settings.order89RepeatPenalty)
            putJsonArray("stop") {
                add("</Order89Prompt>")
                add("\n\n\n\n")
            }
            put("cache_prompt", false)
        }
        return json.toString()
    }

    fun detectBaseIndent(text: String): String {
        return text.split("\n")
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .minByOrNull { it.length } ?: ""
    }

    private val FENCED_BLOCK = Regex("```[^\\n]*\\n([\\s\\S]*?)\\n\\s*```")

    fun extractCodeBlock(output: String): String {
        val matches = FENCED_BLOCK.findAll(output).toList()
        if (matches.isEmpty()) return output
        return matches.joinToString("\n\n") { it.groupValues[1] }
    }

    private val CODE_LINE_PATTERN = Regex(
        """^\s*([{}\[\]()@$<>]|//|/\*|\*|#|<!--|--|%|\{-|""" +
        """public\b|private\b|protected\b|function\b|class\b|interface\b|""" +
        """def\b|fn\b|let\b|const\b|var\b|val\b|if\b|for\b|while\b|""" +
        """return\b|import\b|use\b|package\b|namespace\b|""" +
        """abstract\b|static\b|final\b|override\b|""" +
        """struct\b|enum\b|trait\b|impl\b|type\b|module\b)"""
    )

    private val INDENTED_LINE = Regex("""^[\t ]+\S""")

    private val IDENTIFIER_CODE_PATTERN = Regex(
        """[a-zA-Z_]\w*\s*[(\[]|[a-zA-Z_]\w*\.[a-zA-Z_]|[a-zA-Z_]\w*\s*[+\-*/]?=\s"""
    )

    fun looksLikeCode(line: String): Boolean {
        return CODE_LINE_PATTERN.containsMatchIn(line) ||
            INDENTED_LINE.containsMatchIn(line) ||
            IDENTIFIER_CODE_PATTERN.containsMatchIn(line)
    }

    fun stripLeadingProse(output: String): String {
        val lines = output.split("\n")
        val firstCodeLine = lines.indexOfFirst { it.isNotBlank() && looksLikeCode(it) }
        if (firstCodeLine <= 0) return output
        return lines.drop(firstCodeLine).joinToString("\n")
    }

    fun stripTrailingProse(output: String): String {
        val lines = output.split("\n")
        val lastCodeLine = lines.indexOfLast { it.isNotBlank() && looksLikeCode(it) }
        if (lastCodeLine < 0 || lastCodeLine == lines.lastIndex) return output
        return lines.take(lastCodeLine + 1).joinToString("\n")
    }

    fun cleanOutput(raw: String): String {
        return extractCodeBlock(raw)
            .let { stripLeadingProse(it) }
            .let { stripTrailingProse(it) }
    }

    fun reindentOutput(output: String, selectionIndent: String): String {
        val lines = output.split("\n")
        if (lines.size <= 1) return output

        val baseIndent = detectBaseIndent(output)
        return lines.mapIndexed { index, line ->
            when {
                line.isBlank() -> line
                index == 0 -> line.removePrefix(baseIndent)
                else -> selectionIndent + line.removePrefix(baseIndent)
            }
        }.joinToString("\n")
    }

    fun executeWithTools(
        request: Order89Request,
        settings: Settings,
        toolExecutor: (ToolCall) -> String,
        completionFn: (String, String) -> String,
        onStatusUpdate: (StatusUpdate) -> Unit = {}
    ): Order89Result {
        val maxRounds = settings.order89MaxToolRounds
        val allResults = mutableListOf<ToolResult>()

        for (round in 1..maxRounds) {
            onStatusUpdate(StatusUpdate.WaitingForModel)

            val prompt = buildChatPrompt(request, allResults, includeTools = true)
            val body = buildChatRequestBody(prompt, settings)

            val responseBody: String
            try {
                responseBody = completionFn(body, settings.order89ServerUrl)
            } catch (e: Exception) {
                return Order89Result(success = false, output = e.message ?: "Unknown error")
            }

            val responseJson = try {
                Json.parseToJsonElement(responseBody).jsonObject
            } catch (e: Exception) {
                return Order89Result(success = false, output = "Invalid JSON response: ${e.message}")
            }

            val content = responseJson["content"]?.jsonPrimitive?.contentOrNull
            if (content.isNullOrEmpty()) {
                return Order89Result(success = false, output = "Empty content in response")
            }

            val toolCalls = extractToolCalls(content)

            if (toolCalls.isEmpty()) {
                // Model produced code directly, no Phase 2 needed
                val cleaned = cleanOutput(content)
                return Order89Result(success = true, output = cleaned)
            }

            // Show tool calls in status display, then execute in parallel
            onStatusUpdate(StatusUpdate.ToolCalls(toolCalls))
            val futures = toolCalls.map { call ->
                CompletableFuture.supplyAsync {
                    try {
                        ToolResult(call, toolExecutor(call))
                    } catch (e: Exception) {
                        ToolResult(call, "Error: ${e.message}")
                    }
                }
            }

            val roundResults = futures.map { it.join() }
            allResults.addAll(roundResults)
        }

        // Max rounds reached or all rounds done with results — Phase 2
        onStatusUpdate(StatusUpdate.WaitingForModel)
        val phase2Prompt = buildChatPrompt(request, allResults, includeTools = false)
        val phase2Body = buildChatRequestBody(phase2Prompt, settings)

        val phase2Response: String
        try {
            phase2Response = completionFn(phase2Body, settings.order89ServerUrl)
        } catch (e: Exception) {
            return Order89Result(success = false, output = e.message ?: "Unknown error")
        }

        val phase2Json = try {
            Json.parseToJsonElement(phase2Response).jsonObject
        } catch (e: Exception) {
            return Order89Result(success = false, output = "Invalid JSON response: ${e.message}")
        }

        val phase2Content = phase2Json["content"]?.jsonPrimitive?.contentOrNull
        if (phase2Content.isNullOrEmpty()) {
            return Order89Result(success = false, output = "Empty content in Phase 2 response")
        }

        val cleaned = cleanOutput(phase2Content)
        return Order89Result(success = true, output = cleaned)
    }

    fun execute(request: Order89Request, settings: Settings): Future<Order89Result> {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            try {
                val prompt = buildPrompt(request)
                val body = buildRequestBody(prompt, settings)

                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${settings.order89ServerUrl}/completion"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() != 200) {
                    return@Callable Order89Result(
                        success = false,
                        output = "HTTP ${response.statusCode()}: ${response.body()}"
                    )
                }

                val responseJson = Json.parseToJsonElement(response.body()).jsonObject
                val content = responseJson["content"]?.jsonPrimitive?.contentOrNull

                if (content.isNullOrEmpty()) {
                    return@Callable Order89Result(
                        success = false,
                        output = "Empty content in response"
                    )
                }

                val cleaned = cleanOutput(content)
                Order89Result(success = true, output = cleaned)
            } catch (e: Exception) {
                Order89Result(success = false, output = e.message ?: "Unknown error")
            }
        })
        executor.shutdown()
        return future
    }
}
