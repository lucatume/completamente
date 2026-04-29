package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.services.DebugLog
import com.github.lucatume.completamente.services.Settings
import com.intellij.openapi.application.ApplicationManager
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val PROMPT_FILE_PLACEHOLDER: String = "%%prompt_file%%"

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

data class ProcessRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false
)

data class CappedRead(val text: String, val truncated: Boolean)

/**
 * Holds the live [Process] for an in-flight Order 89 invocation so ESC can destroy it.
 * Setting a process after [cancel] has been called destroys it immediately.
 *
 * [cancel] is non-blocking: it sends SIGTERM synchronously, then dispatches the
 * 250 ms wait + force-kill to a pooled thread. ESC fires from the EDT and must not block
 * the UI if the CLI ignores SIGTERM.
 */
class Order89ProcessSession {
    private val processRef = AtomicReference<Process?>(null)
    private val cancelled = AtomicBoolean(false)

    fun setProcess(process: Process) {
        processRef.set(process)
        // Re-check cancelled after the store: closes the race where cancel() observed a null
        // processRef and returned, while we hadn't yet stored the live process. Without this,
        // a subprocess could outlive a cancelled session.
        if (cancelled.get() && processRef.compareAndSet(process, null)) {
            process.destroyForcibly()
        }
    }

    fun cancel() {
        cancelled.set(true)
        val process = processRef.getAndSet(null) ?: return
        // Snapshot descendants BEFORE terminating the shell wrapper. Otherwise the shell may
        // exit first and reparent its children to init, breaking the parent→child link we use
        // to find them. Pipelines and `cmd &` lose this link otherwise.
        val descendants: List<ProcessHandle> = try {
            process.toHandle().descendants().toList()
        } catch (_: Throwable) {
            emptyList()
        }
        descendants.forEach { runCatching { it.destroy() } }
        process.destroy()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                descendants.forEach { runCatching { if (it.isAlive) it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }
}

internal fun escapeXmlAttr(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&apos;").replace("<", "&lt;").replace(">", "&gt;")

object Order89Executor {

    /** Total file-content embedded in the prompt is capped to keep the agent's context manageable. */
    internal const val MAX_PROMPT_FILE_CHARS: Int = 200_000

    /** When trimming, this many chars on each side of the selection are kept verbatim. */
    internal const val PROMPT_FILE_WINDOW_CHARS: Int = 50_000

    /** Quote a path as a double-quoted string with backslash and quote escapes. */
    fun escapePosixPath(path: String): String =
        "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Render the file content split around the selection. For files within
     * [MAX_PROMPT_FILE_CHARS] the full content is returned. Otherwise a window of
     * [PROMPT_FILE_WINDOW_CHARS] on each side of the selection is kept verbatim and the
     * trimmed regions are replaced with a `[… N chars truncated]` marker.
     */
    internal fun renderFileWindow(content: String, selectionStart: Int, selectionEnd: Int): Pair<String, String> {
        if (content.length <= MAX_PROMPT_FILE_CHARS) {
            return Pair(content.substring(0, selectionStart), content.substring(selectionEnd))
        }
        val beforeStart = maxOf(0, selectionStart - PROMPT_FILE_WINDOW_CHARS)
        val afterEnd = minOf(content.length, selectionEnd + PROMPT_FILE_WINDOW_CHARS)
        val before = buildString {
            if (beforeStart > 0) append("[… $beforeStart chars truncated]\n")
            append(content, beforeStart, selectionStart)
        }
        val after = buildString {
            append(content, selectionEnd, afterEnd)
            if (afterEnd < content.length) append("\n[… ${content.length - afterEnd} chars truncated]")
        }
        return Pair(before, after)
    }

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

    /**
     * Replaces every occurrence of [PROMPT_FILE_PLACEHOLDER] in [command] with [promptFilePath].
     * The path's backslashes and double quotes are escaped so substitution into a double-quoted
     * argument (e.g. `@"%%prompt_file%%"`) yields a single shell-style token after tokenization.
     */
    fun substitutePromptFile(command: String, promptFilePath: String): String {
        val escaped = promptFilePath.replace("\\", "\\\\").replace("\"", "\\\"")
        return command.replace(PROMPT_FILE_PLACEHOLDER, escaped)
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

    /** Maximum bytes captured from a single output stream — prevents OOM from a runaway agent. */
    internal const val MAX_STREAM_CHARS: Int = 8 * 1024 * 1024

    /**
     * Resolved shell used to launch the command. Reads `$SHELL` and falls back to `/bin/sh`
     * if unset/blank.
     */
    internal fun resolveShell(): String =
        System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"

    /**
     * Argv that launches [commandString] under [shell] as an interactive shell. Extracted so
     * tests can pin the contract (the `-i` flag specifically — silently regressing to `-l` or
     * dropping it would change which rc files get sourced and re-introduce the original bug).
     */
    internal fun buildShellArgv(shell: String, commandString: String): List<String> =
        listOf(shell, "-i", "-c", commandString)

    /**
     * Default process runner. Launches [commandString] via `$SHELL -ic` — an interactive
     * shell that sources `~/.zshrc` / `~/.bashrc` so PATH set up by `nvm`/`asdf`/`mise` and
     * other interactive-rc init applies, matching what users see in a terminal tab. Login-only
     * files (`~/.zprofile`, `~/.bash_profile`) are not sourced. Hands the live [Process] to
     * [session] so ESC can destroy it. Blocks until the process exits naturally or is killed.
     *
     * The shell handles tokenization and quoting, so users can write the command with the same
     * quoting they'd use in their terminal.
     */
    internal fun defaultRunProcess(
        commandString: String,
        workingDirectory: File?,
        session: Order89ProcessSession
    ): ProcessRunResult {
        val pb = ProcessBuilder(buildShellArgv(resolveShell(), commandString))
        if (workingDirectory != null) pb.directory(workingDirectory)
        // No TTY → bash prints "cannot set terminal process group" and "no job control".
        // Redirecting stdin from /dev/null silences those warnings before they hit stderr.
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val process = pb.start()
        session.setProcess(process)
        // Drain stdout/stderr concurrently to avoid pipe-buffer deadlocks.
        val outFuture = readerFuture(process.inputStream)
        val errFuture = readerFuture(process.errorStream)
        val exit = process.waitFor()
        val out = outFuture.get()
        val err = errFuture.get()
        return ProcessRunResult(exit, out.text, err.text, out.truncated, err.truncated)
    }

    private fun readerFuture(stream: InputStream): Future<CappedRead> =
        ApplicationManager.getApplication().executeOnPooledThread<CappedRead> {
            readCappedStream(stream, MAX_STREAM_CHARS)
        }

    /**
     * Reads at most [cap] characters from [stream], then drains and discards the rest so the
     * child process's pipe doesn't fill up and block. Always closes the stream. The returned
     * [CappedRead.truncated] is true if any input was discarded — callers must surface this
     * rather than apply a partial result.
     */
    internal fun readCappedStream(stream: InputStream, cap: Int): CappedRead {
        val buf = StringBuilder()
        var capped = false
        stream.bufferedReader().use { reader ->
            val chunk = CharArray(8192)
            while (true) {
                val n = reader.read(chunk)
                if (n < 0) break
                if (!capped) {
                    val take = minOf(n, cap - buf.length)
                    if (take > 0) buf.append(chunk, 0, take)
                    if (buf.length >= cap) capped = true
                } else {
                    // Past the cap: keep draining so the child's stdout pipe doesn't block,
                    // but discard the bytes — we've already decided to fail this invocation.
                }
            }
        }
        return CappedRead(buf.toString(), capped)
    }

    /**
     * Lines emitted by `bash -i` / `zsh -i` when no controlling TTY is attached. They have no
     * bearing on the user's command and only confuse the error UI when surfaced via [truncateError].
     */
    private val SHELL_INIT_NOISE = listOf(
        Regex("""^bash: cannot set terminal process group.*$"""),
        Regex("""^bash: no job control in this shell.*$""")
    )

    internal fun stripShellInitNoise(stderr: String): String =
        stderr.lineSequence()
            .filterNot { line -> SHELL_INIT_NOISE.any { it.containsMatchIn(line) } }
            .joinToString("\n")

    private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")

    /**
     * Best-effort extraction of the program name from a shell command string for debug logging.
     * Skips leading `KEY=value` env-var assignments (which often carry secrets like API keys)
     * so they don't land in idea.log. Returns `<env-only>` if the command has nothing but
     * assignments, `<empty>` for blank input.
     */
    internal fun extractProgramName(commandString: String): String {
        val tokens = commandString.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "<empty>"
        val first = tokens.firstOrNull { !ENV_ASSIGNMENT.matches(it) } ?: return "<env-only>"
        return first
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
        session: Order89ProcessSession,
        runProcess: (String, File?, Order89ProcessSession) -> ProcessRunResult = ::defaultRunProcess
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
