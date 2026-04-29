package com.github.lucatume.completamente.walkthrough

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

/**
 * The DTO crossing the EDT/pooled-thread boundary. Captured on the EDT before the agent is
 * dispatched to a background thread; from this point on, the agent's view of the file is
 * frozen at this snapshot — even if the user edits the document while the agent runs.
 *
 * Indexing on this DTO is **1-indexed** for the line/col fields because the prompt envelope
 * renders them verbatim and the LLM wire format is 1-indexed. The action is responsible for
 * converting from IntelliJ's 0-indexed `Document` API at capture time.
 */
data class WalkthroughRequest(
    val prompt: String,
    val filePath: String,
    val language: String,
    val fileContent: String,
    val selectionStart: Int,        // offset in fileContent
    val selectionEnd: Int,          // offset in fileContent
    val startLine: Int,             // 1-indexed (wire format)
    val startCol: Int,              // 1-indexed (wire format)
    val endLine: Int,               // 1-indexed (wire format)
    val endCol: Int                 // 1-indexed, end-column-exclusive (wire format)
) {
    val selectionText: String get() = fileContent.substring(selectionStart, selectionEnd)
}

/**
 * Result of [WalkthroughExecutor.execute].
 *
 * On success, [walkthrough] holds the parsed tree (or `null` for a legitimately-empty
 * `<Walkthrough></Walkthrough>` block — the action surfaces "no steps" to the user).
 * On failure, [errorMessage] holds the user-facing reason.
 */
data class WalkthroughResult(
    val success: Boolean,
    val walkthrough: Walkthrough? = null,
    val errorMessage: String? = null
)

/**
 * Builds the agent prompt and (in a later task) invokes the configured CLI.
 *
 * Mirrors `Order89Executor`'s shape: prompt envelope built from a frozen document snapshot,
 * file content windowed via the shared `renderFileWindow`, agent invoked via the shared
 * shell-launching primitives in `services/AgentProcess.kt`.
 */
object WalkthroughExecutor {

    fun buildPrompt(request: WalkthroughRequest): String {
        val (before, after) = renderFileWindow(
            request.fileContent,
            request.selectionStart,
            request.selectionEnd
        )
        val selection = request.selectionText
        return buildString {
            appendLine("<WalkthroughPrompt>")
            appendLine("You are a code walkthrough agent. You receive a file with a marked selection range")
            appendLine("and an instruction. You output a guided walkthrough of the code as a single")
            appendLine("<Walkthrough>...</Walkthrough> block.")
            appendLine()
            appendLine("<WalkthroughRules>")
            appendLine("- Output exactly one <Walkthrough> ... </Walkthrough> block. No prose before or after.")
            appendLine("- Each <Step> MUST have:")
            appendLine("    - file=\"<project-relative path, POSIX separators>\"")
            appendLine("    - range=\"startLine:startCol-endLine:endCol\"  (1-indexed; end column is exclusive)")
            appendLine("- <Narration> is optional. Omit the element if the step shows code only.")
            appendLine("- Output at least one <Step>.")
            appendLine("- Steps are presented to the user in document order. The first <Step> should anchor")
            appendLine("  the user at the code they invoked the walkthrough on.")
            appendLine("- Only reference files inside the project. If you need to mention a file outside the")
            appendLine("  project (an SDK, a library, a system header), describe it in the narration text —")
            appendLine("  do NOT use it as a step's file attribute.")
            appendLine("- Do NOT modify any file.")
            appendLine("- Treat the <WalkthroughFileContent> block below as authoritative for the invoked")
            appendLine("  file. The on-disk copy may be stale (the IDE may have unsaved changes).")
            appendLine("</WalkthroughRules>")
            appendLine()
            appendLine("Language: ${request.language}")
            appendLine("File: ${escapePosixPath(request.filePath)}")
            appendLine("Selection: ${request.startLine}:${request.startCol}-${request.endLine}:${request.endCol}  (1-indexed; end column is exclusive)")
            appendLine()
            appendLine("<WalkthroughInstruction>")
            appendLine(request.prompt)
            appendLine("</WalkthroughInstruction>")
            appendLine()
            appendLine("<WalkthroughFileContent>")
            append(before)
            append("<WalkthroughUserSelection>")
            append(selection)
            append("</WalkthroughUserSelection>")
            append(after)
            // Always force a line break before the closing tag so the closing tag never trails
            // the user-selection markers on the same line — matters for empty/short snapshots.
            if (length > 0 && this[length - 1] != '\n') appendLine()
            appendLine("</WalkthroughFileContent>")
            appendLine()
            appendLine("REMINDER: Output ONLY the <Walkthrough> block. Keep file= attributes inside the project.")
            appendLine("</WalkthroughPrompt>")
        }
    }

    private fun truncateError(stderr: String, maxLines: Int = 20, maxChars: Int = 2000): String {
        val trimmed = stripShellInitNoise(stderr).trim()
        if (trimmed.isEmpty()) return "(no stderr output)"
        val lines = trimmed.lines()
        val tail = if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else trimmed
        return if (tail.length > maxChars) "…" + tail.takeLast(maxChars) else tail
    }

    /**
     * Runs the configured CLI for [request], parses its `<Walkthrough>` output, and returns
     * a [WalkthroughResult]. Intended to be called off the EDT (typically inside a
     * `Task.Backgroundable.run`).
     *
     * [runProcess] is injectable for tests; production callers should pass [::defaultRunProcess].
     */
    fun execute(
        request: WalkthroughRequest,
        settings: Settings,
        workingDirectory: File?,
        session: AgentProcessSession,
        runProcess: (String, File?, AgentProcessSession) -> ProcessRunResult = ::defaultRunProcess
    ): WalkthroughResult {
        val command = settings.walkthroughCliCommand
        if (command.isBlank()) {
            return WalkthroughResult(false, errorMessage =
                "Walkthrough command is empty. " +
                    "Configure it in Settings → completamente → Walkthrough."
            )
        }
        if (!command.contains(PROMPT_FILE_PLACEHOLDER)) {
            return WalkthroughResult(false, errorMessage =
                "Walkthrough command must contain $PROMPT_FILE_PLACEHOLDER. " +
                    "Configure it in Settings → completamente → Walkthrough."
            )
        }
        val promptText = DebugLog.timed("Walkthrough prompt build") { buildPrompt(request) }
        val tempFile: Path = Files.createTempFile("walkthrough-", ".md")
        try {
            Files.writeString(tempFile, promptText)
            val absolutePath = tempFile.toAbsolutePath().toString()
            val substituted = substitutePromptFile(command, absolutePath)
            val program = extractProgramName(substituted)
            DebugLog.log("Walkthrough invoking via ${resolveShell()} -ic: $program (${substituted.length} chars, cwd=${workingDirectory?.absolutePath ?: "<system temp>"})")
            val result = try {
                DebugLog.timed("Walkthrough process") { runProcess(substituted, workingDirectory, session) }
            } catch (e: Exception) {
                DebugLog.log("Walkthrough process error: ${e.message}")
                return WalkthroughResult(false, errorMessage = "Failed to launch CLI: ${e.message}")
            }
            DebugLog.log("Walkthrough process exit=${result.exitCode}, stdout=${result.stdout.length} chars, stderr=${result.stderr.length} chars, stdoutTruncated=${result.stdoutTruncated}")
            if (result.exitCode != 0) {
                return WalkthroughResult(false, errorMessage =
                    "CLI exit code ${result.exitCode}: ${truncateError(result.stderr)}"
                )
            }
            if (result.stdoutTruncated) {
                return WalkthroughResult(false, errorMessage =
                    "CLI output exceeded $MAX_STREAM_CHARS chars and was truncated. " +
                        "Refusing to display a partial walkthrough."
                )
            }
            if (result.stdout.isBlank()) {
                return WalkthroughResult(false, errorMessage =
                    "CLI exited 0 but produced no output. Check that the command writes the " +
                        "<Walkthrough> block to stdout."
                )
            }
            val parseResult = WalkthroughParser.parse(result.stdout)
            return if (parseResult.isFailure) {
                val why = parseResult.exceptionOrNull()?.message ?: "unknown parse error"
                WalkthroughResult(false, errorMessage =
                    "Could not parse the walkthrough: $why\n\nRaw output:\n${result.stdout.take(2000)}"
                )
            } else {
                WalkthroughResult(true, walkthrough = parseResult.getOrNull())
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
