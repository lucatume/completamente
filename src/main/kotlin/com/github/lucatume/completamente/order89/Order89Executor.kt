package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.services.AgentProcessSession
import com.github.lucatume.completamente.services.DebugLog
import com.github.lucatume.completamente.services.MAX_STREAM_CHARS
import com.github.lucatume.completamente.services.PROMPT_FILE_PLACEHOLDER
import com.github.lucatume.completamente.services.ProcessRunResult
import com.github.lucatume.completamente.services.Settings
import com.github.lucatume.completamente.services.defaultRunProcess
import com.github.lucatume.completamente.services.escapePosixPath
import com.github.lucatume.completamente.services.extractProgramName
import com.github.lucatume.completamente.services.renderFileWindow
import com.github.lucatume.completamente.services.resolveShell
import com.github.lucatume.completamente.services.stripShellInitNoise
import com.github.lucatume.completamente.services.substitutePromptFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class Order89Request(
    val prompt: String,
    val filePath: String,
    val language: String,
    val fileContent: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val referencedFilePaths: List<String>
) {
    val selectionText: String get() = fileContent.substring(selectionStart, selectionEnd)
}

data class Order89Result(
    val success: Boolean,
    val output: String
)

object Order89Executor {

    fun buildPrompt(request: Order89Request): String {
        val (before, after) = renderFileWindow(request.fileContent, request.selectionStart, request.selectionEnd)
        val selection = request.selectionText
        return buildString {
            appendLine("<Order89Prompt>")
            appendLine("You are a code transformation agent. You receive a file with a marked selection range and an instruction.")
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
            appendLine("- Do NOT modify any file directly. Return the modified selection content as a single")
            appendLine("  fenced code block. Order 89 may be invoked on multiple ranges concurrently and the")
            appendLine("  IDE applies the replacement based on original line positions; modifying files")
            appendLine("  yourself would corrupt other concurrent invocations.")
            appendLine("- Treat the <Order89FileContent> block below as the authoritative content of the file")
            appendLine("  under edit. The on-disk copy may be stale (IDE has unsaved changes). Use the path")
            appendLine("  only to reason about location; do not re-read this file from disk.")
            appendLine("</Order89Rules>")
            appendLine()
            appendLine("Language: ${request.language}")
            appendLine("File: ${escapePosixPath(request.filePath)}")
            appendLine("Selection: ${request.startLine}:${request.startCol}-${request.endLine}:${request.endCol}")
            appendLine()
            if (request.referencedFilePaths.isNotEmpty()) {
                appendLine("<Order89ReferencedFiles>")
                appendLine("The following files are referenced by the file under edit. Read them as needed")
                appendLine("to understand the APIs and types available, so your generated code calls real")
                appendLine("methods with correct signatures.")
                for (p in request.referencedFilePaths) {
                    appendLine(escapePosixPath(p))
                }
                appendLine("</Order89ReferencedFiles>")
                appendLine()
            }
            appendLine("<Order89Instruction>")
            appendLine(request.prompt)
            appendLine("</Order89Instruction>")
            appendLine()
            // Live document snapshot taken at invocation time. Authoritative — do NOT re-read the
            // file from disk; the IDE may have unsaved changes and the on-disk copy can be stale.
            appendLine("<Order89FileContent>")
            append(before)
            append("<Order89UserSelection>")
            append(selection)
            append("</Order89UserSelection>")
            append(after)
            if (request.fileContent.isNotEmpty() && !request.fileContent.endsWith('\n')) appendLine()
            appendLine("</Order89FileContent>")
            appendLine()
            appendLine("REMINDER: Match the file's documentation style. Return ONLY the replacement code in a single fenced block.")
            appendLine("</Order89Prompt>")
        }
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

    fun matchTrailingNewlines(originalSelection: String, replacement: String): String {
        val targetCount = originalSelection.takeLastWhile { it == '\n' }.length
        return replacement.trimEnd('\n') + "\n".repeat(targetCount)
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

    private fun truncateError(stderr: String, maxLines: Int = 20, maxChars: Int = 2000): String {
        val trimmed = stripShellInitNoise(stderr).trim()
        if (trimmed.isEmpty()) return "(no stderr output)"
        val lines = trimmed.lines()
        val tail = if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else trimmed
        return if (tail.length > maxChars) "…" + tail.takeLast(maxChars) else tail
    }

    /**
     * Runs the configured CLI for [request] and returns the cleaned replacement output
     * (or an error). Intended to be called off the EDT.
     *
     * [runProcess] is injectable for tests; production callers should pass [::defaultRunProcess].
     */
    fun execute(
        request: Order89Request,
        settings: Settings,
        workingDirectory: File?,
        session: AgentProcessSession,
        runProcess: (String, File?, AgentProcessSession) -> ProcessRunResult = ::defaultRunProcess
    ): Order89Result {
        val command = settings.order89CliCommand
        if (!command.contains(PROMPT_FILE_PLACEHOLDER)) {
            return Order89Result(false, "Order 89 command must contain $PROMPT_FILE_PLACEHOLDER. " +
                "Configure it in Settings → completamente → Order 89.")
        }
        if (command.isBlank()) {
            return Order89Result(false, "Order 89 command is empty. " +
                "Configure it in Settings → completamente → Order 89.")
        }
        val promptText = DebugLog.timed("Order89 prompt build") { buildPrompt(request) }
        val tempFile: Path = Files.createTempFile("order89-", ".md")
        try {
            Files.writeString(tempFile, promptText)
            val absolutePath = tempFile.toAbsolutePath().toString()
            val substituted = substitutePromptFile(command, absolutePath)
            // Log only the program word + length so secrets a user inlines in the command
            // (API keys, tokens) don't land in idea.log. Full command lives in user settings.
            val program = extractProgramName(substituted)
            DebugLog.log("Order89 invoking via ${resolveShell()} -ic: $program (${substituted.length} chars, cwd=${workingDirectory?.absolutePath ?: "<system temp>"})")
            val result = try {
                DebugLog.timed("Order89 process") { runProcess(substituted, workingDirectory, session) }
            } catch (e: Exception) {
                DebugLog.log("Order89 process error: ${e.message}")
                return Order89Result(false, "Failed to launch CLI: ${e.message}")
            }
            DebugLog.log("Order89 process exit=${result.exitCode}, stdout=${result.stdout.length} chars, stderr=${result.stderr.length} chars, stdoutTruncated=${result.stdoutTruncated}")
            if (result.exitCode != 0) {
                return Order89Result(false, "CLI exit code ${result.exitCode}: ${truncateError(result.stderr)}")
            }
            if (result.stdoutTruncated) {
                return Order89Result(false,
                    "CLI output exceeded $MAX_STREAM_CHARS chars and was truncated. " +
                        "Refusing to apply a partial replacement."
                )
            }
            if (result.stdout.isBlank()) {
                return Order89Result(false,
                    "CLI exited 0 but produced no output. Check that the command writes the " +
                        "replacement code to stdout."
                )
            }
            val cleaned = cleanOutput(result.stdout)
            return if (cleaned.isBlank()) {
                Order89Result(false,
                    "CLI exited 0 but the output contained no extractable code. " +
                        "Raw output:\n${result.stdout.take(2000)}"
                )
            } else {
                Order89Result(true, cleaned)
            }
        } finally {
            try {
                Files.deleteIfExists(tempFile)
            } catch (_: Exception) {
                // best-effort cleanup
            }
        }
    }
}
