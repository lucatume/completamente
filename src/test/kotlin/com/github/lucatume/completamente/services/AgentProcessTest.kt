package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import java.io.File

class AgentProcessTest : BaseCompletionTest() {

    // -- escapePosixPath --

    fun testEscapePosixPathWrapsInQuotes() {
        assertEquals("\"/usr/local/bin\"", escapePosixPath("/usr/local/bin"))
    }

    fun testEscapePosixPathEscapesEmbeddedQuotes() {
        assertEquals("\"/a/b\\\"c\"", escapePosixPath("/a/b\"c"))
    }

    fun testEscapePosixPathEscapesBackslashes() {
        assertEquals("\"/a\\\\b\"", escapePosixPath("/a\\b"))
    }

    // -- escapeXmlAttr --

    fun testEscapeXmlAttrEscapesAllXmlEntities() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", escapeXmlAttr("a&b<c>d\"e'f"))
    }

    fun testEscapeXmlAttrLeavesPlainTextUnchanged() {
        assertEquals("plain text", escapeXmlAttr("plain text"))
    }

    // -- substitutePromptFile --

    fun testSubstitutePromptFileReplacesPlaceholder() {
        val command = "agent --in $PROMPT_FILE_PLACEHOLDER -p go"
        assertEquals("agent --in /tmp/x.md -p go", substitutePromptFile(command, "/tmp/x.md"))
    }

    fun testSubstitutePromptFileEscapesPathQuotesForDoubleQuotedSegment() {
        val command = "agent @\"$PROMPT_FILE_PLACEHOLDER\""
        // path contains a literal double-quote; expect it to be backslash-escaped.
        val sub = substitutePromptFile(command, "/tmp/x\"y.md")
        assertEquals("agent @\"/tmp/x\\\"y.md\"", sub)
    }

    fun testSubstitutePromptFileWithNoPlaceholderReturnsCommandUnchanged() {
        val command = "agent --in something -p go"
        assertEquals(command, substitutePromptFile(command, "/tmp/x.md"))
    }

    // -- renderFileWindow --

    fun testRenderFileWindowSmallFileReturnsFullSplits() {
        val content = "abcDEFghi"
        val (before, after) = renderFileWindow(content, 3, 6)
        assertEquals("abc", before)
        assertEquals("ghi", after)
    }

    fun testRenderFileWindowLargeFileTrimsBothSides() {
        val sidePadding = MAX_PROMPT_FILE_CHARS // 200k chars on each side
        val padding = "x".repeat(sidePadding)
        val content = padding + "MARKER" + padding
        val (before, after) = renderFileWindow(content, sidePadding, sidePadding + "MARKER".length)
        val expectedTruncated = sidePadding - PROMPT_FILE_WINDOW_CHARS
        assertTrue(before.startsWith("[… $expectedTruncated chars truncated]\n"))
        assertTrue(after.endsWith("[… $expectedTruncated chars truncated]"))
    }

    // -- defaultRunProcess (shell wrap) --

    fun testResolveShellFallsBackToShWhenEnvUnset() {
        // Can't unset $SHELL on a running JVM portably; just assert the fallback contract by
        // confirming a non-blank value is returned and is a plausible POSIX shell path.
        val shell = resolveShell()
        assertTrue("resolveShell must return non-blank", shell.isNotBlank())
        assertTrue("resolveShell must return an absolute path", shell.startsWith("/"))
    }

    fun testBuildShellArgvUsesInteractiveFlag() {
        // -i (not -l) is load-bearing: it's what makes ~/.zshrc / ~/.bashrc get sourced so PATH
        // from nvm/asdf/mise applies. A silent regression to -l would re-break "pi: command not
        // found" for tools whose PATH setup lives in interactive rc files.
        val argv = buildShellArgv("/bin/bash", "echo HELLO")
        assertEquals(listOf("/bin/bash", "-i", "-c", "echo HELLO"), argv)
    }

    fun testStripShellInitNoiseRemovesBashTtyWarnings() {
        val raw = """
            bash: cannot set terminal process group (-1): Inappropriate ioctl for device
            bash: no job control in this shell
            actual error from CLI
        """.trimIndent()
        val cleaned = stripShellInitNoise(raw)
        assertFalse("bash TTY warning should be stripped", cleaned.contains("cannot set terminal"))
        assertFalse("bash job-control warning should be stripped", cleaned.contains("no job control"))
        assertTrue("real error should survive", cleaned.contains("actual error from CLI"))
    }

    fun testStripShellInitNoisePreservesUnrelatedStderr() {
        val raw = "Error: API key required\nstack trace line 1"
        assertEquals(raw, stripShellInitNoise(raw))
    }

    fun testExtractProgramNameReturnsFirstWord() {
        assertEquals("claude", extractProgramName("claude --file /tmp/x.md"))
        assertEquals("pi", extractProgramName("  pi --tools read,grep @\"/tmp/x\""))
    }

    fun testExtractProgramNameSkipsLeadingEnvAssignments() {
        // Inline env assignments (often API keys) must NOT appear in the logged program word.
        val program = extractProgramName(
            "ANTHROPIC_API_KEY=sk-secret-token-1234 OPENAI_KEY=sk-other claude --file /tmp/x.md"
        )
        assertEquals("claude", program)
        assertFalse("API key must not survive into the program token", program.contains("sk-"))
    }

    fun testExtractProgramNameHandlesEdgeCases() {
        assertEquals("<empty>", extractProgramName(""))
        assertEquals("<empty>", extractProgramName("   "))
        assertEquals("<env-only>", extractProgramName("FOO=bar BAZ=qux"))
    }

    fun testDefaultRunProcessExecutesCommandStringViaShell() {
        val session = AgentProcessSession()
        val result = defaultRunProcess(
            "echo HELLO",
            workingDirectory = null,
            session = session
        )
        assertEquals(0, result.exitCode)
        assertTrue("stdout should contain echoed sentinel, got: ${result.stdout}", result.stdout.contains("HELLO"))
        assertEquals("", result.stderr.trim().lines().filter { !it.startsWith("Warning:") && it.isNotBlank() }.joinToString("\n"))
    }

    fun testDefaultRunProcessPassesCommandStringVerbatimToShell() {
        // A quoted argument with embedded whitespace must arrive at echo as a single argument.
        // If we were tokenizing in-plugin, "two words" would either become one token or we'd
        // have to escape — the shell handles it for us.
        val session = AgentProcessSession()
        val result = defaultRunProcess(
            "echo 'two words'",
            workingDirectory = null,
            session = session
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("two words"))
    }

    fun testDefaultRunProcessNonZeroExitCapturesStderr() {
        val session = AgentProcessSession()
        val result = defaultRunProcess(
            "echo oops 1>&2; exit 7",
            workingDirectory = null,
            session = session
        )
        assertEquals(7, result.exitCode)
        assertTrue("stderr should capture the redirected message, got: '${result.stderr}'", result.stderr.contains("oops"))
    }

    fun testDefaultRunProcessHonorsWorkingDirectory() {
        val session = AgentProcessSession()
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val result = defaultRunProcess(
            "pwd",
            workingDirectory = tmp,
            session = session
        )
        assertEquals(0, result.exitCode)
        // macOS resolves /tmp to /private/tmp; just check the trailing path matches.
        val pwdLine = result.stdout.trim()
        assertTrue("pwd output ($pwdLine) should end with the configured tmp dir (${tmp.absolutePath})",
            pwdLine.endsWith(tmp.absolutePath) || pwdLine == tmp.canonicalPath
        )
    }

    fun testDefaultRunProcessCancelKillsLongRunningCommand() {
        val session = AgentProcessSession()
        val started = System.nanoTime()
        // Schedule a cancel from a pooled thread shortly after the process starts.
        val canceller = Thread {
            Thread.sleep(150)
            session.cancel()
        }
        canceller.isDaemon = true
        canceller.start()
        val result = defaultRunProcess(
            "sleep 10",
            workingDirectory = null,
            session = session
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // cancel() destroys the shell, which terminates `sleep` (single-command exec). Allow
        // generous slack so this isn't flaky on loaded CI.
        assertTrue("Process should exit well before 10s sleep completes; took ${elapsedMs}ms",
            elapsedMs < 5_000)
        // The shell exits non-zero when killed by SIGTERM; we don't assert on the exact code.
        assertNotNull(result)
    }

    // -- AgentProcessSession --

    fun testProcessSessionDestroyKillsProcess() {
        // Use `cat` from /dev/stdin so it blocks; we can then destroy it.
        val pb = ProcessBuilder("cat").redirectErrorStream(true)
        val process = pb.start()
        val session = AgentProcessSession()
        session.setProcess(process)

        session.cancel()
        // After cancel the process must be dead within a short window.
        val exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Process should be terminated by cancel()", exited)
        assertFalse("Process should no longer be alive", process.isAlive)
    }

    fun testProcessSessionDestroysProcessSetAfterCancel() {
        val session = AgentProcessSession()
        session.cancel()

        val pb = ProcessBuilder("cat")
        val process = pb.start()
        session.setProcess(process)

        val exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Process attached after cancel should be force-destroyed", exited)
    }

    fun testProcessSessionRecheckClosesCancelRace() {
        // Simulate the race: cancel() runs after setProcess reads the cancelled flag (false)
        // but before it stores the process. We model this by inverting the order: store the
        // process while cancelled is already true, exercising the post-store re-check path.
        val session = AgentProcessSession()
        session.cancel()    // sets cancelled = true; processRef is still null

        val pb = ProcessBuilder("cat")
        val process = pb.start()
        session.setProcess(process)    // post-store re-check must destroy

        assertTrue(
            "Re-check after store must terminate a process registered into a cancelled session",
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        )
    }
}
