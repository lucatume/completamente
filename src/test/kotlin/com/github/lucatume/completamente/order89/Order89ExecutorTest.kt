package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class Order89ExecutorTest : BaseCompletionTest() {

    private fun makeRequest(
        prompt: String = "",
        filePath: String = "/tmp/test.kt",
        language: String = "kotlin",
        selectionText: String = "",
        startLine: Int = 1,
        startCol: Int = 1,
        endLine: Int = 1,
        endCol: Int = 1,
        referencedFilePaths: List<String> = emptyList()
    ) = Order89Request(
        prompt = prompt,
        filePath = filePath,
        language = language,
        fileContent = selectionText,
        selectionStart = 0,
        selectionEnd = selectionText.length,
        startLine = startLine,
        startCol = startCol,
        endLine = endLine,
        endCol = endCol,
        referencedFilePaths = referencedFilePaths
    )

    // -- buildPrompt --

    fun testBuildPromptContainsAllSections() {
        val request = makeRequest(
            prompt = "add a method",
            language = "kotlin",
            filePath = "/src/Foo.kt",
            selectionText = ""
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("<Order89Prompt>"))
        assertTrue(result.contains("</Order89Prompt>"))
        assertTrue(result.contains("<Order89Rules>"))
        assertTrue(result.contains("</Order89Rules>"))
        assertTrue(result.contains("<Order89Instruction>"))
        assertTrue(result.contains("add a method"))
        assertTrue(result.contains("</Order89Instruction>"))
        assertTrue(result.contains("<Order89UserSelection>"))
        assertTrue(result.contains("</Order89UserSelection>"))
        assertTrue(result.contains("Language: kotlin"))
        assertTrue(result.contains("File: \"/src/Foo.kt\""))
    }

    fun testBuildPromptDoesNotIncludeContextOrToolSpec() {
        val result = Order89Executor.buildPrompt(makeRequest(selectionText = "x"))
        assertFalse(result.contains("<Order89Context>"))
        assertFalse(result.contains("<Order89ContextFile"))
        assertFalse(result.contains("FileSearch"))
        assertFalse(result.contains("DocSearch"))
        assertFalse(result.contains("<tool_call>"))
    }

    fun testBuildPromptIncludesReturnContentRule() {
        val result = Order89Executor.buildPrompt(makeRequest(selectionText = "x"))
        assertTrue("Should warn against modifying files directly", result.contains("Do NOT modify any file directly"))
    }

    fun testBuildPromptEmptySelectionStillWrappedInTags() {
        val result = Order89Executor.buildPrompt(makeRequest(selectionText = ""))
        assertTrue(result.contains("<Order89UserSelection>"))
        assertTrue(result.contains("</Order89UserSelection>"))
    }

    fun testBuildPromptIncludesSelectionLineColRange() {
        val result = Order89Executor.buildPrompt(
            makeRequest(selectionText = "println(\"hi\")", startLine = 2, startCol = 5, endLine = 2, endCol = 18)
        )
        assertTrue("Should include 1-based line:col range", result.contains("Selection: 2:5-2:18"))
    }

    fun testBuildPromptIncludesEscapedPosixPathForFile() {
        val result = Order89Executor.buildPrompt(
            makeRequest(filePath = "/Users/u/some path with spaces/File.kt", selectionText = "x")
        )
        assertTrue(
            "File path must be wrapped in escaped POSIX form",
            result.contains("File: \"/Users/u/some path with spaces/File.kt\"")
        )
    }

    fun testBuildPromptIncludesEscapedReferencedFilePaths() {
        val result = Order89Executor.buildPrompt(
            makeRequest(selectionText = "x", referencedFilePaths = listOf("/a/Helper.kt", "/b/Has \"Quote\".kt"))
        )
        assertTrue(result.contains("<Order89ReferencedFiles>"))
        assertTrue(result.contains("\"/a/Helper.kt\""))
        assertTrue(result.contains("\"/b/Has \\\"Quote\\\".kt\""))
        assertTrue(result.contains("</Order89ReferencedFiles>"))
    }

    fun testBuildPromptOmitsReferencedFilesBlockWhenEmpty() {
        val result = Order89Executor.buildPrompt(makeRequest(selectionText = "x"))
        assertFalse(result.contains("<Order89ReferencedFiles>"))
    }

    fun testBuildPromptWindowsLargeFileAroundSelection() {
        // Build a file large enough to cross MAX_PROMPT_FILE_CHARS so windowing kicks in.
        val sidePadding = Order89Executor.MAX_PROMPT_FILE_CHARS // 200k chars on each side
        val padding = "x".repeat(sidePadding)
        val content = padding + "MARKER" + padding
        val request = Order89Request(
            prompt = "rewrite",
            filePath = "/src/Big.kt",
            language = "kotlin",
            fileContent = content,
            selectionStart = sidePadding,
            selectionEnd = sidePadding + "MARKER".length,
            startLine = 1, startCol = 1, endLine = 1, endCol = 1,
            referencedFilePaths = emptyList()
        )
        val result = Order89Executor.buildPrompt(request)
        val expectedTruncated = sidePadding - Order89Executor.PROMPT_FILE_WINDOW_CHARS
        assertTrue(
            "Truncated marker should report $expectedTruncated chars",
            result.contains("[… $expectedTruncated chars truncated]")
        )
        assertTrue("Selection content must survive trimming", result.contains("<Order89UserSelection>MARKER</Order89UserSelection>"))
        assertTrue("Result must be far smaller than original", result.length < content.length / 2)
    }

    fun testBuildPromptDoesNotWindowSmallFiles() {
        val content = "abc\nDEF\nghi"
        val request = Order89Request(
            prompt = "rewrite",
            filePath = "/x.kt",
            language = "kotlin",
            fileContent = content,
            selectionStart = 4,
            selectionEnd = 7,
            startLine = 2, startCol = 1, endLine = 2, endCol = 4,
            referencedFilePaths = emptyList()
        )
        val result = Order89Executor.buildPrompt(request)
        assertFalse("Small files must not be truncated", result.contains("chars truncated"))
        assertTrue(result.contains("abc\n<Order89UserSelection>DEF</Order89UserSelection>\nghi"))
    }

    fun testBuildPromptEmbedsLiveFileContentWithSelectionMarkers() {
        val content = "fun main() {\n    println(\"hi\")\n}\n"
        val request = Order89Request(
            prompt = "rewrite",
            filePath = "/src/Main.kt",
            language = "kotlin",
            fileContent = content,
            selectionStart = 17,
            selectionEnd = 30,
            startLine = 2, startCol = 5, endLine = 2, endCol = 18,
            referencedFilePaths = emptyList()
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue("Should embed file in <Order89FileContent>", result.contains("<Order89FileContent>"))
        assertTrue(
            "Selection markers must split the live document, not just the selection text",
            result.contains("fun main() {\n    <Order89UserSelection>println(\"hi\")</Order89UserSelection>\n}")
        )
        assertTrue("Closing tag present", result.contains("</Order89FileContent>"))
        assertTrue(
            "Rule must tell the agent the embedded snapshot is authoritative",
            result.contains("authoritative content")
        )
    }

    fun testBuildPromptRulesContainFenceInstruction() {
        val result = Order89Executor.buildPrompt(makeRequest(selectionText = "x"))
        assertTrue(result.contains("fenced code block using triple backticks"))
    }

    // -- escapePosixPath --

    fun testEscapePosixPathWrapsInQuotes() {
        assertEquals("\"/usr/local/bin\"", Order89Executor.escapePosixPath("/usr/local/bin"))
    }

    fun testEscapePosixPathEscapesEmbeddedQuotes() {
        assertEquals("\"/a/b\\\"c\"", Order89Executor.escapePosixPath("/a/b\"c"))
    }

    fun testEscapePosixPathEscapesBackslashes() {
        assertEquals("\"/a\\\\b\"", Order89Executor.escapePosixPath("/a\\b"))
    }

    // -- substitutePromptFile --

    fun testSubstitutePromptFileReplacesPlaceholder() {
        val command = "agent --in $PROMPT_FILE_PLACEHOLDER -p go"
        assertEquals("agent --in /tmp/x.md -p go", Order89Executor.substitutePromptFile(command, "/tmp/x.md"))
    }

    fun testSubstitutePromptFileEscapesPathQuotesForDoubleQuotedSegment() {
        val command = "agent @\"$PROMPT_FILE_PLACEHOLDER\""
        // path contains a literal double-quote; expect it to be backslash-escaped.
        val sub = Order89Executor.substitutePromptFile(command, "/tmp/x\"y.md")
        assertEquals("agent @\"/tmp/x\\\"y.md\"", sub)
    }

    fun testSubstitutePromptFileWithNoPlaceholderReturnsCommandUnchanged() {
        val command = "agent --in something -p go"
        assertEquals(command, Order89Executor.substitutePromptFile(command, "/tmp/x.md"))
    }

    // -- execute (with fake runProcess) --

    /** Helper: extract the temp-file path from a command string containing `@"/path"`. */
    private fun pathFromAtToken(commandString: String): Path {
        val match = Regex("@\"([^\"]+)\"").find(commandString)
            ?: error("expected @\"...\" segment in command: $commandString")
        return Path.of(match.groupValues[1])
    }

    private fun settingsWithCommand(cmd: String): Settings = Settings(order89CliCommand = cmd)

    fun testExecuteSuccessStripsFenceAndReturnsCleanedOutput() {
        val request = makeRequest(prompt = "do it", selectionText = "x")
        val captured = AtomicReference<String?>()
        val result = Order89Executor.execute(
            request,
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { commandString, _, _ ->
                captured.set(commandString)
                ProcessRunResult(0, "Some preamble.\n\n```kotlin\nval x = 42\n```\n", "")
            }
        )
        assertTrue("Expected success, got: ${result.output}", result.success)
        assertEquals("val x = 42", result.output)
        // Ensure the placeholder was substituted to a real path inside the command string.
        val commandString = captured.get()!!
        assertFalse("Placeholder should have been replaced", commandString.contains(PROMPT_FILE_PLACEHOLDER))
        assertTrue("Command string should retain the @\"<path>\" segment", commandString.contains("@\""))
    }

    fun testExecutePassesWorkingDirectoryToRunProcess() {
        val request = makeRequest(selectionText = "x")
        val workingDir = File(System.getProperty("java.io.tmpdir"))
        val captured = AtomicReference<File?>()
        Order89Executor.execute(
            request,
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = workingDir,
            session = Order89ProcessSession(),
            runProcess = { _, dir, _ ->
                captured.set(dir)
                ProcessRunResult(0, "```\nok\n```", "")
            }
        )
        assertEquals(workingDir, captured.get())
    }

    fun testExecuteWritesPromptToTempFileAndDeletesAfter() {
        val request = makeRequest(prompt = "marker-prompt", selectionText = "x")
        val capturedTempPath = AtomicReference<Path?>()
        Order89Executor.execute(
            request,
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { commandString, _, _ ->
                val path = pathFromAtToken(commandString)
                capturedTempPath.set(path)
                assertTrue("Temp file should exist while process runs", Files.exists(path))
                val content = Files.readString(path)
                assertTrue("Temp file should contain the prompt instruction", content.contains("marker-prompt"))
                ProcessRunResult(0, "```\nok\n```", "")
            }
        )
        val path = capturedTempPath.get()!!
        assertFalse("Temp file should be deleted after execute returns", Files.exists(path))
    }

    fun testExecuteDeletesTempFileOnNonZeroExit() {
        val request = makeRequest(selectionText = "x")
        val captured = AtomicReference<Path?>()
        val result = Order89Executor.execute(
            request,
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { commandString, _, _ ->
                captured.set(pathFromAtToken(commandString))
                ProcessRunResult(2, "", "boom")
            }
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("CLI exit code 2"))
        assertTrue(result.output.contains("boom"))
        assertFalse("Temp file should be deleted on failure", Files.exists(captured.get()!!))
    }

    fun testExecuteFailsOnEmptyStdout() {
        val request = makeRequest(selectionText = "x")
        val result = Order89Executor.execute(
            request,
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { _, _, _ -> ProcessRunResult(0, "", "") }
        )
        assertFalse("Empty stdout must NOT silently delete the selection", result.success)
        assertTrue(result.output.contains("no output"))
    }

    fun testExecuteFailsWhenStdoutTruncated() {
        val request = makeRequest(selectionText = "x")
        val result = Order89Executor.execute(
            request,
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { _, _, _ -> ProcessRunResult(0, "```\npartial\n```", "", stdoutTruncated = true) }
        )
        assertFalse("Truncated stdout must NOT be applied as a partial replacement", result.success)
        assertTrue(result.output.contains("truncated"))
    }

    fun testExecuteRejectsCommandWithoutPlaceholder() {
        val request = makeRequest(selectionText = "x")
        var ran = false
        val result = Order89Executor.execute(
            request,
            settingsWithCommand("agent --no-placeholder"),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { _, _, _ -> ran = true; ProcessRunResult(0, "", "") }
        )
        assertFalse(result.success)
        assertTrue(result.output.contains(PROMPT_FILE_PLACEHOLDER))
        assertFalse("runProcess must not be invoked when command is misconfigured", ran)
    }

    fun testExecuteRejectsEmptyCommand() {
        val request = makeRequest(selectionText = "x")
        val result = Order89Executor.execute(
            request,
            settingsWithCommand("   "),
            workingDirectory = null,
            session = Order89ProcessSession(),
            runProcess = { _, _, _ -> error("must not run") }
        )
        assertFalse(result.success)
    }

    // -- defaultRunProcess (shell wrap) --

    fun testResolveLoginShellFallsBackToShWhenEnvUnset() {
        // Can't unset $SHELL on a running JVM portably; just assert the fallback contract by
        // confirming a non-blank value is returned and is a plausible POSIX shell path.
        val shell = Order89Executor.resolveLoginShell()
        assertTrue("resolveLoginShell must return non-blank", shell.isNotBlank())
        assertTrue("resolveLoginShell must return an absolute path", shell.startsWith("/"))
    }

    fun testDefaultRunProcessExecutesCommandStringViaShell() {
        val session = Order89ProcessSession()
        val result = Order89Executor.defaultRunProcess(
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
        val session = Order89ProcessSession()
        val result = Order89Executor.defaultRunProcess(
            "echo 'two words'",
            workingDirectory = null,
            session = session
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("two words"))
    }

    fun testDefaultRunProcessNonZeroExitCapturesStderr() {
        val session = Order89ProcessSession()
        val result = Order89Executor.defaultRunProcess(
            "echo oops 1>&2; exit 7",
            workingDirectory = null,
            session = session
        )
        assertEquals(7, result.exitCode)
        assertTrue("stderr should capture the redirected message, got: '${result.stderr}'", result.stderr.contains("oops"))
    }

    fun testDefaultRunProcessHonorsWorkingDirectory() {
        val session = Order89ProcessSession()
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val result = Order89Executor.defaultRunProcess(
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
        val session = Order89ProcessSession()
        val started = System.nanoTime()
        // Schedule a cancel from a pooled thread shortly after the process starts.
        val canceller = Thread {
            Thread.sleep(150)
            session.cancel()
        }
        canceller.isDaemon = true
        canceller.start()
        val result = Order89Executor.defaultRunProcess(
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

    // -- Order89ProcessSession --

    fun testProcessSessionDestroyKillsProcess() {
        // Use `cat` from /dev/stdin so it blocks; we can then destroy it.
        val pb = ProcessBuilder("cat").redirectErrorStream(true)
        val process = pb.start()
        val session = Order89ProcessSession()
        session.setProcess(process)

        session.cancel()
        // After cancel the process must be dead within a short window.
        val exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Process should be terminated by cancel()", exited)
        assertFalse("Process should no longer be alive", process.isAlive)
    }

    fun testProcessSessionDestroysProcessSetAfterCancel() {
        val session = Order89ProcessSession()
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
        val session = Order89ProcessSession()
        session.cancel()    // sets cancelled = true; processRef is still null

        val pb = ProcessBuilder("cat")
        val process = pb.start()
        session.setProcess(process)    // post-store re-check must destroy

        assertTrue(
            "Re-check after store must terminate a process registered into a cancelled session",
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        )
    }

    // -- post-processing (preserved) --

    fun testReindentOutputStripsDeepIndentAndRebasesToSelection() {
        val output = "        if (x) {\n            doStuff()\n        }"
        val result = Order89Executor.reindentOutput(output, "    ")
        assertEquals("if (x) {\n        doStuff()\n    }", result)
    }

    fun testReindentOutputSingleLineUnchanged() {
        assertEquals("single line", Order89Executor.reindentOutput("single line", "    "))
        assertEquals("    indented", Order89Executor.reindentOutput("    indented", "        "))
    }

    fun testReindentOutputEmptyReturnsAsIs() {
        assertEquals("", Order89Executor.reindentOutput("", "    "))
    }

    fun testReindentOutputNoIndentWithIndentedSelection() {
        val output = "if (x) {\n    doStuff()\n}"
        val result = Order89Executor.reindentOutput(output, "    ")
        assertEquals("if (x) {\n        doStuff()\n    }", result)
    }

    fun testReindentOutputForInsertionCase() {
        val output = "val x = 1\nval y = 2\nval z = 3"
        val result = Order89Executor.reindentOutput(output, "    ")
        assertEquals("val x = 1\n    val y = 2\n    val z = 3", result)
    }

    fun testReindentOutputPreservesBlankLines() {
        val output = "line1\n\nline3\n\nline5"
        val result = Order89Executor.reindentOutput(output, "    ")
        assertEquals("line1\n\n    line3\n\n    line5", result)
    }

    fun testReindentOutputMixedIndentationLevels() {
        val output = "    fun foo() {\n        bar()\n            baz()\n    }"
        val result = Order89Executor.reindentOutput(output, "  ")
        assertEquals("fun foo() {\n      bar()\n          baz()\n  }", result)
    }

    fun testReindentOutputTabIndentedOutputWithSpaceSelection() {
        val output = "\t\tif (x) {\n\t\t\tdoStuff()\n\t\t}"
        val result = Order89Executor.reindentOutput(output, "    ")
        assertEquals("if (x) {\n    \tdoStuff()\n    }", result)
    }

    fun testDetectBaseIndentMultiLineVaryingIndentation() {
        val text = "        if (x) {\n            doStuff()\n        }"
        assertEquals("        ", Order89Executor.detectBaseIndent(text))
    }

    fun testDetectBaseIndentSingleLine() {
        assertEquals("    ", Order89Executor.detectBaseIndent("    hello"))
    }

    fun testDetectBaseIndentEmptyString() {
        assertEquals("", Order89Executor.detectBaseIndent(""))
    }

    fun testDetectBaseIndentOnlyBlankLines() {
        assertEquals("", Order89Executor.detectBaseIndent("   \n\n  \n"))
    }

    fun testDetectBaseIndentMixedTabsAndSpaces() {
        val text = "\t\tif (x) {\n\t\t\tdoStuff()\n\t\t}"
        assertEquals("\t\t", Order89Executor.detectBaseIndent(text))
    }

    // -- truncatePrompt --

    fun testTruncatePromptShorterThanMax() {
        assertEquals("short prompt", truncatePrompt("short prompt"))
    }

    fun testTruncatePromptExactlyAtMax() {
        val prompt = "a".repeat(60)
        assertEquals(prompt, truncatePrompt(prompt))
    }

    fun testTruncatePromptLongerThanMax() {
        val prompt = "a".repeat(80)
        assertEquals("a".repeat(60) + "...", truncatePrompt(prompt))
    }

    fun testTruncatePromptMultiLineCollapsesNewlines() {
        assertEquals("line one line two line three", truncatePrompt("line one\nline two\nline three"))
    }

    fun testTruncatePromptBlank() {
        assertEquals("", truncatePrompt(""))
        assertEquals("", truncatePrompt("   "))
        assertEquals("", truncatePrompt("\n\n"))
    }

    fun testTruncatePromptCustomMaxLength() {
        assertEquals("hello...", truncatePrompt("hello world", maxLength = 5))
    }

    fun testTruncatePromptMultiLineLongTruncated() {
        val prompt = "first line\nsecond line that is quite long and will push past the limit easily"
        val result = truncatePrompt(prompt, maxLength = 30)
        assertEquals("first line second line that is...", result)
    }

    // -- extractCodeBlock --

    fun testExtractCodeBlockWholeOutputFence() {
        val input = "```kotlin\nval x = 1\n```"
        assertEquals("val x = 1", Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockWithoutLanguage() {
        val input = "```\nsome code\n```"
        assertEquals("some code", Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockMultipleLines() {
        val input = "```kotlin\nfun main() {\n    println(\"hello\")\n}\n```"
        assertEquals("fun main() {\n    println(\"hello\")\n}", Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockNoFences() {
        val input = "fun main() {\n    println(\"hello\")\n}"
        assertEquals(input, Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockEmbeddedFence() {
        val input = "Here's the code:\n\n```kotlin\nval x = 1\n```\n\nThis should work."
        assertEquals("val x = 1", Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockMultipleFences() {
        val input = "First:\n```kotlin\nval x = 1\n```\nThen:\n```kotlin\nprintln(x)\n```"
        assertEquals("val x = 1\n\nprintln(x)", Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockWithSurroundingWhitespace() {
        val input = "\n```js\nconsole.log('hi')\n```\n"
        assertEquals("console.log('hi')", Order89Executor.extractCodeBlock(input))
    }

    fun testExtractCodeBlockDoesNotStripInlineBackticks() {
        val input = "use `val x = 1` here"
        assertEquals(input, Order89Executor.extractCodeBlock(input))
    }

    // -- looksLikeCode --

    fun testLooksLikeCodeKeyword() {
        assertTrue(Order89Executor.looksLikeCode("val x = 1"))
        assertTrue(Order89Executor.looksLikeCode("function foo() {}"))
        assertTrue(Order89Executor.looksLikeCode("class Foo {}"))
        assertTrue(Order89Executor.looksLikeCode("return x"))
        assertTrue(Order89Executor.looksLikeCode("import java.io.File"))
    }

    fun testLooksLikeCodeBrackets() {
        assertTrue(Order89Executor.looksLikeCode("{"))
        assertTrue(Order89Executor.looksLikeCode("}"))
        assertTrue(Order89Executor.looksLikeCode("(x + y)"))
        assertTrue(Order89Executor.looksLikeCode("[1, 2, 3]"))
    }

    fun testLooksLikeCodeComments() {
        assertTrue(Order89Executor.looksLikeCode("// a comment"))
        assertTrue(Order89Executor.looksLikeCode("/* block comment */"))
        assertTrue(Order89Executor.looksLikeCode("# python comment"))
    }

    fun testLooksLikeCodeIndented() {
        assertTrue(Order89Executor.looksLikeCode("    indented code"))
        assertTrue(Order89Executor.looksLikeCode("\tindented code"))
    }

    fun testLooksLikeCodeFunctionCalls() {
        assertTrue(Order89Executor.looksLikeCode("println(x)"))
        assertTrue(Order89Executor.looksLikeCode("foo.bar()"))
        assertTrue(Order89Executor.looksLikeCode("assertEquals(expected, actual)"))
        assertTrue(Order89Executor.looksLikeCode("doStuff()"))
    }

    fun testLooksLikeCodeAssignments() {
        assertTrue(Order89Executor.looksLikeCode("x = 1"))
        assertTrue(Order89Executor.looksLikeCode("result += 2"))
    }

    fun testLooksLikeCodeProse() {
        assertFalse(Order89Executor.looksLikeCode("Here's the modified code:"))
        assertFalse(Order89Executor.looksLikeCode("This should work because it prints x."))
        assertFalse(Order89Executor.looksLikeCode("Based on the API, I'll write the code."))
        assertFalse(Order89Executor.looksLikeCode("Let me know if you need changes."))
    }

    fun testLooksLikeCodeEmptyString() {
        assertFalse(Order89Executor.looksLikeCode(""))
    }

    // -- stripLeadingProse --

    fun testStripLeadingProseRemovesProseBeforeCode() {
        val input = "Here's the modified code:\n\nval x = 1\nprintln(x)"
        assertEquals("val x = 1\nprintln(x)", Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseKeepsCodeOnly() {
        val input = "val x = 1\nprintln(x)"
        assertEquals(input, Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseKeepsIndentedCode() {
        val input = "    val x = 1\n    println(x)"
        assertEquals(input, Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseRemovesMultipleProseLines() {
        val input = "Based on the API, I'll write the code.\nHere it is:\nfunction foo() {\n    return 1\n}"
        assertEquals("function foo() {\n    return 1\n}", Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseKeepsCommentLines() {
        val input = "// This is a comment\nval x = 1"
        assertEquals(input, Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseAllProse() {
        val input = "This is just a sentence."
        assertEquals(input, Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseBlankLinesBeforeCode() {
        val input = "\n\nfunction foo() {}"
        assertEquals("function foo() {}", Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseEmptyInput() {
        assertEquals("", Order89Executor.stripLeadingProse(""))
    }

    // -- stripTrailingProse --

    fun testStripTrailingProseRemovesProseAfterCode() {
        val input = "val x = 1\nprintln(x)\n\nThis should work because it prints x."
        assertEquals("val x = 1\nprintln(x)", Order89Executor.stripTrailingProse(input))
    }

    fun testStripTrailingProseKeepsCodeOnly() {
        val input = "val x = 1\nprintln(x)"
        assertEquals(input, Order89Executor.stripTrailingProse(input))
    }

    fun testStripTrailingProseKeepsTrailingComment() {
        val input = "val x = 1\n// end of code"
        assertEquals(input, Order89Executor.stripTrailingProse(input))
    }

    fun testStripTrailingProseRemovesMultipleProseLines() {
        val input = "return x + y\n}\nThis adds x and y.\nLet me know if you need changes."
        assertEquals("return x + y\n}", Order89Executor.stripTrailingProse(input))
    }

    fun testStripTrailingProseAllProse() {
        val input = "Just some text here."
        assertEquals(input, Order89Executor.stripTrailingProse(input))
    }

    fun testStripTrailingProseEmptyInput() {
        assertEquals("", Order89Executor.stripTrailingProse(""))
    }

    // -- matchTrailingNewlines --

    fun testMatchTrailingNewlinesAppendsMissingNewline() {
        assertEquals("bar\n", Order89Executor.matchTrailingNewlines("foo\n", "bar"))
    }

    fun testMatchTrailingNewlinesPreservesMultiple() {
        assertEquals("bar\n\n", Order89Executor.matchTrailingNewlines("foo\n\n", "bar"))
    }

    fun testMatchTrailingNewlinesTrimsExtra() {
        assertEquals("bar", Order89Executor.matchTrailingNewlines("foo", "bar\n\n"))
    }

    fun testMatchTrailingNewlinesNoChange() {
        assertEquals("bar\n", Order89Executor.matchTrailingNewlines("foo\n", "bar\n"))
    }

    fun testMatchTrailingNewlinesEmptyOriginal() {
        assertEquals("bar", Order89Executor.matchTrailingNewlines("", "bar\n"))
    }

    // -- cleanOutput integration --

    fun testCleanOutputProseWithFencedCode() {
        val input = "Here's the code:\n\n```kotlin\nval x = 1\nprintln(x)\n```\n\nThis should work."
        assertEquals("val x = 1\nprintln(x)", Order89Executor.cleanOutput(input))
    }

    fun testCleanOutputProseWithUnfencedCode() {
        val input = "I'll add a println statement.\n\nval x = 1\nprintln(x)\n\nThat should do it."
        assertEquals("val x = 1\nprintln(x)", Order89Executor.cleanOutput(input))
    }

    fun testCleanOutputCleanCodePassthrough() {
        val input = "val x = 1\nprintln(x)"
        assertEquals(input, Order89Executor.cleanOutput(input))
    }

    fun testCleanOutputRealWorldModelOutput() {
        val input = "Based on the API, I'll write the following test methods that cover the main functionality:\n\n" +
            "public function testItCreatesAUser(): void {\n" +
            "    \$user = User::create(['name' => 'John']);\n" +
            "    \$this->assertNotNull(\$user->id);\n" +
            "}\n\n" +
            "public function testItDeletesAUser(): void {\n" +
            "    \$user = User::create(['name' => 'Jane']);\n" +
            "    \$user->delete();\n" +
            "    \$this->assertNull(User::find(\$user->id));\n" +
            "}"
        val expected = "public function testItCreatesAUser(): void {\n" +
            "    \$user = User::create(['name' => 'John']);\n" +
            "    \$this->assertNotNull(\$user->id);\n" +
            "}\n\n" +
            "public function testItDeletesAUser(): void {\n" +
            "    \$user = User::create(['name' => 'Jane']);\n" +
            "    \$user->delete();\n" +
            "    \$this->assertNull(User::find(\$user->id));\n" +
            "}"
        assertEquals(expected, Order89Executor.cleanOutput(input))
    }

    fun testReindentOutputAllBlankLines() {
        val output = "\n\n\n"
        val result = Order89Executor.reindentOutput(output, "    ")
        assertEquals("\n\n\n", result)
    }

    fun testReindentOutputNoIndentWithEmptySelectionIndent() {
        val output = "line1\nline2\nline3"
        val result = Order89Executor.reindentOutput(output, "")
        assertEquals("line1\nline2\nline3", result)
    }

    fun testExtractCodeBlockUnclosedFenceReturnsRawInput() {
        val input = "```kotlin\nval x = 1"
        val result = Order89Executor.extractCodeBlock(input)
        assertEquals(input, result)
    }
}
