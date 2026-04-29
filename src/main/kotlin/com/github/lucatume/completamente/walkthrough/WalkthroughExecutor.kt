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
            appendLine("You are a senior code-walkthrough agent. The user has selected code in their IDE")
            appendLine("and asked a question about it. Produce a thorough, multi-step guided tour that")
            appendLine("teaches them how the code works in context — not a surface-level restatement of")
            appendLine("what the selection literally says.")
            appendLine()
            appendLine("<WalkthroughResearch>")
            appendLine("Investigate before you answer. You have full read access to the project — use it.")
            appendLine("Your working directory is the project root, so grep / find / file reads against")
            appendLine("relative paths just work.")
            appendLine("- Use your tools freely during this investigation phase. Tool calls are not part")
            appendLine("  of your final answer — only the <Walkthrough> block is.")
            appendLine("- The <WalkthroughFileContent> block below is the authoritative snapshot of the")
            appendLine("  file the user invoked you on (the on-disk copy may be stale; the IDE may have")
            appendLine("  unsaved changes). For every OTHER file you cite, read it from disk so the line")
            appendLine("  numbers and identifiers you quote are real.")
            appendLine("- Trace outwards from the selection: what does it call, what calls it, what")
            appendLine("  types/contracts does it depend on?")
            appendLine("- If the selection crosses a process or language boundary (HTTP/REST/RPC/IPC/CLI/")
            appendLine("  shell-out/database call) and the OTHER side of that boundary lives in this")
            appendLine("  project, you MUST land at least one <Step> in that other side. A walkthrough of")
            appendLine("  a frontend `fetch('/api/x')` that never surfaces the server-side handler is")
            appendLine("  shallow and unacceptable.")
            appendLine("- Use grep / find / your file-search tools to locate unfamiliar symbols across")
            appendLine("  the project. Search broadly — endpoint strings, function names, type names.")
            appendLine("- Don't guess. If a tool gives you the line, cite the line. If you can't verify")
            appendLine("  a claim, leave it out rather than invent.")
            appendLine("</WalkthroughResearch>")
            appendLine()
            appendLine("<WalkthroughFocus>")
            appendLine("Spend the user's attention on business logic — the decisions, contracts, side")
            appendLine("effects, error paths, and state transitions that make the code do what it does.")
            appendLine("Do NOT spend <Step>s on boilerplate that the language or framework demands but")
            appendLine("that carries no decision-relevant information.")
            appendLine("- Skip: imports, package declarations, getters / setters / simple property")
            appendLine("  accessors, auto-generated `equals` / `hashCode` / `toString`, trivial")
            appendLine("  constructors that only assign their parameters, DI / factory wiring whose only")
            appendLine("  job is to hand a dependency over, parameter null-guards that just rethrow,")
            appendLine("  one-line logging statements that aren't part of the contract, generated code,")
            appendLine("  and obvious type annotations.")
            appendLine("- Cite: branches, loops with non-trivial conditions, network / IO / DB calls,")
            appendLine("  state mutations, error handling that changes outcome, contract-bearing")
            appendLine("  validation, retries / fallbacks, concurrency primitives, and the matching peer")
            appendLine("  on the other side of any wire boundary.")
            appendLine("- If the user's selection itself IS boilerplate (e.g. they highlighted an import")
            appendLine("  block), still anchor the first <Step> there — but immediately fan out to the")
            appendLine("  business-logic site that uses it, and spend the bulk of the walkthrough there.")
            appendLine("- These categories are presumptions, not bans. If a \"skip\" line is itself the")
            appendLine("  contract-bearing decision the user's question hinges on — a DI binding wires")
            appendLine("  the wrong impl, an import shadows a stdlib name, a getter hides an IO call,")
            appendLine("  a constructor triggers a network round-trip — cite it.")
            appendLine("- A useful rule of thumb: if removing the line would not change observable behaviour")
            appendLine("  (only compile success), it's probably not worth a step on its own.")
            appendLine("</WalkthroughFocus>")
            appendLine()
            appendLine("<WalkthroughDepth>")
            appendLine("- A non-trivial selection should typically produce 4–10 <Step>s. One-liner")
            appendLine("  selections may need fewer; selections that span an integration boundary often")
            appendLine("  need more.")
            appendLine("- Each <Step> anchors on the smallest meaningful range that supports its point —")
            appendLine("  usually one function, one block, or a single significant statement. Do NOT")
            appendLine("  anchor a <Step> on a whole file or a whole class.")
            appendLine("- The first <Step> anchors on the user's original selection. Subsequent steps")
            appendLine("  fan outward: callees, callers, type definitions, the matching peer on the")
            appendLine("  other side of any wire boundary, error paths, fallback paths.")
            appendLine("- If a thorough investigation finds no callers, callees, or boundary peers — the")
            appendLine("  selection is genuinely self-contained — a smaller walkthrough is correct. Do")
            appendLine("  NOT pad with manufactured connections, and do NOT cite files you didn't open.")
            appendLine("</WalkthroughDepth>")
            appendLine()
            appendLine("<WalkthroughNarrationStyle>")
            appendLine("Each <Narration> explains the *role* of the cited code in the user's mental model")
            appendLine("— the why, the contract, the side effects, the failure modes — not what the code")
            appendLine("literally says (the user can read it).")
            appendLine("- Shallow: \"This calls `fetch()` to get the manifest.\"")
            appendLine("- Useful: \"Issues a nonce-authenticated REST call. The matching handler at")
            appendLine("  `class-rest-controller.php:48` validates the nonce against the current WP user;")
            appendLine("  a 403 here means the page-baked nonce expired (tab sat open > 24h).\"")
            appendLine("- Narration markdown subset: `code` for inline code, **bold** sparingly,")
            appendLine("  *italic* for term introductions, [label](https://...) for links (http/https/")
            appendLine("  mailto only). Do NOT emit raw HTML tags — they display verbatim.")
            appendLine("</WalkthroughNarrationStyle>")
            appendLine()
            appendLine("<WalkthroughRules>")
            appendLine("- Your FINAL answer must contain exactly one <Walkthrough> ... </Walkthrough>")
            appendLine("  block and nothing else. No prose before or after. (Tool-use turns during your")
            appendLine("  investigation are unaffected — only the final message is constrained.)")
            appendLine("- Each <Step> MUST have:")
            appendLine("    - file=\"<project-relative path, POSIX separators>\"")
            appendLine("    - range=\"startLine:startCol-endLine:endCol\"  (1-indexed; end column is exclusive)")
            appendLine("- <Narration> is optional but strongly recommended for non-trivial steps.")
            appendLine("- Output at least one <Step>.")
            appendLine("- Steps are shown to the user one at a time, navigated with Next/Previous, in")
            appendLine("  document order.")
            appendLine("- Only reference files inside the project. If you need to mention a file outside")
            appendLine("  the project (an SDK, a vendor library, a system header), describe it in the")
            appendLine("  narration — do NOT use it as a step's file attribute.")
            appendLine("- Do NOT modify any file.")
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
            appendLine("REMINDER: Investigate before answering — read related files, trace the boundaries,")
            appendLine("verify line numbers. Use tools as needed during investigation. Your FINAL message")
            appendLine("must contain ONLY the <Walkthrough> block, with file= attributes inside the project.")
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
