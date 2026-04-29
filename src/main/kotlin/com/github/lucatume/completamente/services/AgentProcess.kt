package com.github.lucatume.completamente.services

import com.intellij.openapi.application.ApplicationManager
import java.io.File
import java.io.InputStream
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Token used in agent CLI commands. Substituted by the runtime with the path of the prompt file
 * the agent should read. Order 89 and Walkthrough share this contract.
 */
const val PROMPT_FILE_PLACEHOLDER: String = "%%prompt_file%%"

/** Total file-content embedded in agent prompts is capped to keep the agent's context manageable. */
const val MAX_PROMPT_FILE_CHARS: Int = 200_000

/** When trimming, this many chars on each side of the selection are kept verbatim. */
const val PROMPT_FILE_WINDOW_CHARS: Int = 50_000

/** Maximum bytes captured from a single output stream — prevents OOM from a runaway agent. */
const val MAX_STREAM_CHARS: Int = 8 * 1024 * 1024

/** Result of a single CLI invocation: exit code, captured output, and truncation flags. */
data class ProcessRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false
)

/** Captured stream contents plus a flag indicating whether the cap was hit. */
data class CappedRead(val text: String, val truncated: Boolean)

/**
 * Holds the live [Process] for an in-flight agent invocation so external code can destroy it.
 * Setting a process after [cancel] has been called destroys it immediately.
 *
 * [cancel] is non-blocking: it sends SIGTERM synchronously, then dispatches the
 * 250 ms wait + force-kill to a pooled thread. ESC fires from the EDT and must not block
 * the UI if the CLI ignores SIGTERM.
 */
class AgentProcessSession {
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

/** Quote a path as a double-quoted POSIX shell string with backslash and quote escapes. */
fun escapePosixPath(path: String): String =
    "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Escape a string for inclusion as an XML attribute value or text node. */
fun escapeXmlAttr(value: String): String =
    value.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Render the file content split around the selection. For files within
 * [MAX_PROMPT_FILE_CHARS] the full content is returned. Otherwise a window of
 * [PROMPT_FILE_WINDOW_CHARS] on each side of the selection is kept verbatim and the
 * trimmed regions are replaced with a `[… N chars truncated]` marker.
 */
fun renderFileWindow(content: String, selectionStart: Int, selectionEnd: Int): Pair<String, String> {
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

/**
 * Replaces every occurrence of [PROMPT_FILE_PLACEHOLDER] in [command] with [promptFilePath].
 * The path's backslashes and double quotes are escaped so substitution into a double-quoted
 * argument (e.g. `@"%%prompt_file%%"`) yields a single shell-style token after tokenization.
 */
fun substitutePromptFile(command: String, promptFilePath: String): String {
    val escaped = promptFilePath.replace("\\", "\\\\").replace("\"", "\\\"")
    return command.replace(PROMPT_FILE_PLACEHOLDER, escaped)
}

/**
 * Resolved shell used to launch agent commands. Reads `$SHELL` and falls back to `/bin/sh`
 * if unset/blank.
 */
fun resolveShell(): String =
    System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"

/**
 * Argv that launches [commandString] under [shell] as an interactive shell. Extracted so
 * tests can pin the contract (the `-i` flag specifically — silently regressing to `-l` or
 * dropping it would change which rc files get sourced and re-introduce the original PATH bug).
 */
fun buildShellArgv(shell: String, commandString: String): List<String> =
    listOf(shell, "-i", "-c", commandString)

/**
 * Default agent process runner. Launches [commandString] via `$SHELL -ic` — an interactive
 * shell that sources `~/.zshrc` / `~/.bashrc` so PATH set up by `nvm`/`asdf`/`mise` and
 * other interactive-rc init applies, matching what users see in a terminal tab. Login-only
 * files (`~/.zprofile`, `~/.bash_profile`) are not sourced. Hands the live [Process] to
 * [session] so callers can destroy it. Blocks until the process exits naturally or is killed.
 *
 * The shell handles tokenization and quoting, so users can write the command with the same
 * quoting they'd use in their terminal.
 */
fun defaultRunProcess(
    commandString: String,
    workingDirectory: File?,
    session: AgentProcessSession
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
fun readCappedStream(stream: InputStream, cap: Int): CappedRead {
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
 * bearing on the user's command and only confuse error UIs when surfaced verbatim.
 */
private val SHELL_INIT_NOISE = listOf(
    Regex("""^bash: cannot set terminal process group.*$"""),
    Regex("""^bash: no job control in this shell.*$""")
)

fun stripShellInitNoise(stderr: String): String =
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
fun extractProgramName(commandString: String): String {
    val tokens = commandString.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return "<empty>"
    val first = tokens.firstOrNull { !ENV_ASSIGNMENT.matches(it) } ?: return "<env-only>"
    return first
}
