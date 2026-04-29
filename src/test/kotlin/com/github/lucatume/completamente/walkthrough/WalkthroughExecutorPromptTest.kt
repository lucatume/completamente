package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.AgentProcessSession
import com.github.lucatume.completamente.services.MAX_PROMPT_FILE_CHARS
import com.github.lucatume.completamente.services.PROMPT_FILE_PLACEHOLDER
import com.github.lucatume.completamente.services.PROMPT_FILE_WINDOW_CHARS
import com.github.lucatume.completamente.services.ProcessRunResult
import com.github.lucatume.completamente.services.Settings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class WalkthroughExecutorPromptTest : BaseCompletionTest() {

    private fun makeRequest(
        prompt: String = "explain this",
        filePath: String = "/tmp/Foo.kt",
        language: String = "kotlin",
        fileContent: String = "x",
        selectionStart: Int = 0,
        selectionEnd: Int = 1,
        startLine: Int = 1,
        startCol: Int = 1,
        endLine: Int = 1,
        endCol: Int = 2
    ) = WalkthroughRequest(
        prompt = prompt,
        filePath = filePath,
        language = language,
        fileContent = fileContent,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        startLine = startLine,
        startCol = startCol,
        endLine = endLine,
        endCol = endCol
    )

    fun testPromptContainsAllRequiredSections() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue(result.contains("<WalkthroughPrompt>"))
        assertTrue(result.contains("</WalkthroughPrompt>"))
        assertTrue(result.contains("<WalkthroughRules>"))
        assertTrue(result.contains("</WalkthroughRules>"))
        assertTrue(result.contains("<WalkthroughInstruction>"))
        assertTrue(result.contains("</WalkthroughInstruction>"))
        assertTrue(result.contains("<WalkthroughFileContent>"))
        assertTrue(result.contains("</WalkthroughFileContent>"))
        assertTrue(result.contains("<WalkthroughUserSelection>"))
        assertTrue(result.contains("</WalkthroughUserSelection>"))
    }

    fun testPromptIncludesUserInstructionVerbatim() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest(prompt = "Walk me through this code"))
        assertTrue(result.contains("Walk me through this code"))
    }

    fun testPromptIncludesLanguageAndFileLines() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest(language = "kotlin", filePath = "/src/Foo.kt"))
        assertTrue(result.contains("Language: kotlin"))
        assertTrue(result.contains("File: \"/src/Foo.kt\""))
    }

    fun testPromptUsesOneIndexedRangesOnTheWire() {
        // The internal model (request) carries 1-indexed values from the action layer; verify
        // the prompt renders them verbatim under the "Selection:" header.
        val result = WalkthroughExecutor.buildPrompt(
            makeRequest(startLine = 12, startCol = 5, endLine = 19, endCol = 1)
        )
        assertTrue("Selection range must appear 1-indexed on the wire, got: ${result.lines().firstOrNull { it.startsWith("Selection:") }}",
            result.contains("Selection: 12:5-19:1")
        )
    }

    fun testPromptRulesContainOneIndexedHint() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Rules must clarify the indexing convention to the LLM",
            result.contains("1-indexed")
        )
    }

    fun testPromptRulesForbidOutsideProjectFiles() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Rules must instruct the LLM to keep file paths inside the project",
            result.contains("inside the project")
        )
    }

    fun testPromptRulesRequireSingleWalkthroughBlock() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue(result.contains("<Walkthrough>"))
        assertTrue("Rules must mention the closing tag pairing",
            result.contains("</Walkthrough>")
        )
    }

    fun testPromptEmbedsFileContentWithSelectionMarkers() {
        val content = "fun main() {\n    println(\"hi\")\n}\n"
        val request = makeRequest(
            fileContent = content,
            selectionStart = 17,
            selectionEnd = 30,
            startLine = 2, startCol = 5, endLine = 2, endCol = 18
        )
        val result = WalkthroughExecutor.buildPrompt(request)
        assertTrue(
            "Selection markers must split the live document, not just the selection text",
            result.contains("fun main() {\n    <WalkthroughUserSelection>println(\"hi\")</WalkthroughUserSelection>\n}")
        )
    }

    fun testPromptWindowsLargeFileAroundSelection() {
        val sidePadding = MAX_PROMPT_FILE_CHARS // 200k chars on each side
        val padding = "x".repeat(sidePadding)
        val content = padding + "MARKER" + padding
        val request = makeRequest(
            fileContent = content,
            selectionStart = sidePadding,
            selectionEnd = sidePadding + "MARKER".length
        )
        val result = WalkthroughExecutor.buildPrompt(request)
        val expectedTruncated = sidePadding - PROMPT_FILE_WINDOW_CHARS
        assertTrue(result.contains("[… $expectedTruncated chars truncated]"))
        assertTrue(result.contains("<WalkthroughUserSelection>MARKER</WalkthroughUserSelection>"))
    }

    fun testPromptCaretOnlyEmptyLineProducesNonDegenerateRange() {
        // Simulate a caret-only invocation on an empty line: action has set the wire range to
        // L:1-L:2 (max-clamp ensures non-degenerate). The selection text is empty.
        val result = WalkthroughExecutor.buildPrompt(
            makeRequest(
                fileContent = "",
                selectionStart = 0,
                selectionEnd = 0,
                startLine = 1, startCol = 1, endLine = 1, endCol = 2
            )
        )
        assertTrue(result.contains("Selection: 1:1-1:2"))
        assertTrue(result.contains("<WalkthroughUserSelection></WalkthroughUserSelection>"))
    }

    fun testPromptWarnsAgentNotToModifyFiles() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Walkthrough is read-only — the LLM must not modify any file",
            result.contains("Do NOT modify")
        )
    }

    fun testPromptDeclaresEmbeddedFileContentAsAuthoritative() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("LLM must be told the embedded snapshot is authoritative over disk",
            result.contains("authoritative")
        )
    }

    fun testPromptIncludesReminderToOutputOnlyTheBlock() {
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Reminder line must reinforce the single-block output rule",
            result.contains("REMINDER") && result.contains("Walkthrough> block")
        )
    }

    fun testPromptInstructsAgentToInvestigateBeforeAnswering() {
        // Shallow output is the failure mode we're guarding against. The prompt must push the
        // agent to use its file-read/grep tools, not just paraphrase the embedded selection.
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Prompt must contain a research/investigation section",
            result.contains("<WalkthroughResearch>") && result.contains("</WalkthroughResearch>")
        )
        assertTrue("Prompt must tell the agent to read related files from disk",
            result.contains("read it from disk")
        )
        assertTrue("Reminder must echo the investigate instruction",
            result.contains("Investigate before answering")
        )
    }

    fun testPromptRequiresCrossingIntegrationBoundaries() {
        // The headline failure mode: a frontend call's walkthrough never lands in the matching
        // server handler. The prompt must explicitly require it AND illustrate it with a
        // concrete negative example — the example is the load-bearing piece a future "shorten
        // the prompt" refactor is most likely to silently weaken.
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Prompt must explicitly require landing a step on the other side of process/language boundaries",
            result.contains("other side") && result.contains("boundary")
        )
        assertTrue("Prompt must include a concrete cross-boundary negative example so the rule is illustrated, not just stated",
            result.contains("fetch(") && result.contains("shallow")
        )
    }

    fun testPromptSetsDepthExpectations() {
        // Without a step-count target the agent tends to emit 1-2 trivial steps.
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Prompt must contain a depth/step-count guidance section",
            result.contains("<WalkthroughDepth>")
        )
        assertTrue("Prompt should suggest a 4–10 step range for non-trivial selections",
            result.contains("4–10")
        )
    }

    fun testPromptInstructsAgentToSkipBoilerplate() {
        // Without an explicit "skip boilerplate" rule, the agent tends to dedicate steps to
        // imports, getters/setters, trivial constructors, DI wiring — the structural scaffolding
        // around the logic rather than the logic itself. The user wants steps on what the code
        // *decides*, not on what the language requires it to *declare*.
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Prompt must contain a focus/scope section",
            result.contains("<WalkthroughFocus>") && result.contains("</WalkthroughFocus>")
        )
        assertTrue("Prompt must explicitly steer away from boilerplate and toward business logic",
            result.contains("boilerplate") && result.contains("business logic")
        )
        assertTrue("Prompt must enumerate concrete categories to skip so the rule is illustrated, not just stated",
            result.contains("imports") && result.contains("getters")
        )
        assertTrue("Prompt must keep the carve-out for selections that ARE boilerplate so a future shortening doesn't drop it",
            result.contains("anchor the first <Step> there")
        )
        assertTrue("Prompt must keep the observable-behaviour heuristic that anchors the rule",
            result.contains("observable behaviour")
        )
    }

    fun testPromptForbidsShallowNarration() {
        // Narration that just paraphrases the code is the visible symptom of the shallow-output
        // bug. Prompt must steer toward the *why*, not the *what*.
        val result = WalkthroughExecutor.buildPrompt(makeRequest())
        assertTrue("Prompt must contain a narration-style section",
            result.contains("<WalkthroughNarrationStyle>")
        )
        assertTrue("Prompt should contrast shallow vs useful narration with a concrete example",
            result.contains("Shallow:") && result.contains("Useful:")
        )
    }

    // -- execute (with fake runProcess) --

    private fun pathFromAtToken(commandString: String): Path {
        val match = Regex("@\"([^\"]+)\"").find(commandString)
            ?: error("expected @\"...\" segment in command: $commandString")
        return Path.of(match.groupValues[1])
    }

    private fun settingsWithCommand(cmd: String): Settings = Settings(walkthroughCliCommand = cmd)

    private fun successfulOutput(): String = """
        <Walkthrough>
          <Step id="1" file="src/Foo.kt" range="1:1-2:1"><Narration>step</Narration></Step>
        </Walkthrough>
    """.trimIndent()

    fun testExecuteSuccessReturnsParsedWalkthrough() {
        val captured = AtomicReference<String?>()
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { commandString, _, _ ->
                captured.set(commandString)
                ProcessRunResult(0, successfulOutput(), "")
            }
        )
        assertTrue("expected success, got: ${result.errorMessage}", result.success)
        assertNotNull(result.walkthrough)
        assertEquals("src/Foo.kt", result.walkthrough!!.root.range.file)
        // Placeholder substituted to a real path
        assertFalse(captured.get()!!.contains(PROMPT_FILE_PLACEHOLDER))
    }

    fun testExecuteWritesPromptToTempFileAndDeletesAfter() {
        val capturedPath = AtomicReference<Path?>()
        WalkthroughExecutor.execute(
            makeRequest(prompt = "marker-prompt"),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { commandString, _, _ ->
                val path = pathFromAtToken(commandString)
                capturedPath.set(path)
                assertTrue("Temp file should exist while process runs", Files.exists(path))
                val content = Files.readString(path)
                assertTrue("Temp file should contain the prompt", content.contains("marker-prompt"))
                ProcessRunResult(0, successfulOutput(), "")
            }
        )
        val path = capturedPath.get()!!
        assertFalse("Temp file should be deleted after execute returns", Files.exists(path))
    }

    fun testExecuteRejectsCommandWithoutPlaceholder() {
        var ran = false
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent --no-placeholder"),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { _, _, _ -> ran = true; ProcessRunResult(0, successfulOutput(), "") }
        )
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains(PROMPT_FILE_PLACEHOLDER))
        assertFalse("runProcess must not be invoked when command is misconfigured", ran)
    }

    fun testExecuteRejectsBlankCommand() {
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("   "),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { _, _, _ -> error("must not run") }
        )
        assertFalse(result.success)
    }

    fun testExecuteFailsOnNonZeroExit() {
        val capturedPath = AtomicReference<Path?>()
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { cmd, _, _ ->
                capturedPath.set(pathFromAtToken(cmd))
                ProcessRunResult(2, "", "boom")
            }
        )
        assertFalse(result.success)
        val msg = result.errorMessage!!
        assertTrue(msg.contains("CLI exit code 2"))
        assertTrue(msg.contains("boom"))
        assertFalse("Temp file should be deleted on failure", Files.exists(capturedPath.get()!!))
    }

    fun testExecuteFailsOnTruncatedStdout() {
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { _, _, _ -> ProcessRunResult(0, successfulOutput(), "", stdoutTruncated = true) }
        )
        assertFalse("Truncated output must not be silently parsed", result.success)
        assertTrue(result.errorMessage!!.contains("truncated"))
    }

    fun testExecuteFailsOnBlankStdout() {
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { _, _, _ -> ProcessRunResult(0, "", "") }
        )
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("no output"))
    }

    fun testExecuteFailsOnParseError() {
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { _, _, _ -> ProcessRunResult(0, "no walkthrough block here", "") }
        )
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
        // The error should include the raw output (truncated) so the user can see what came back.
        assertTrue("Error message should expose the raw output, got: ${result.errorMessage}",
            result.errorMessage!!.contains("no walkthrough block here")
        )
    }

    fun testExecuteSucceedsWithEmptyWalkthroughBlock() {
        // The parser legitimately allows an empty <Walkthrough></Walkthrough> block; the
        // executor returns success with walkthrough=null, and the action layer surfaces the
        // "no steps" notification to the user.
        val result = WalkthroughExecutor.execute(
            makeRequest(),
            settingsWithCommand("agent @\"$PROMPT_FILE_PLACEHOLDER\""),
            workingDirectory = null,
            session = AgentProcessSession(),
            runProcess = { _, _, _ -> ProcessRunResult(0, "<Walkthrough></Walkthrough>", "") }
        )
        assertTrue("expected success, got: ${result.errorMessage}", result.success)
        assertNull(result.walkthrough)
    }

    fun testPromptEmptyFileContentClosesFileContentTagOnItsOwnLine() {
        val result = WalkthroughExecutor.buildPrompt(
            makeRequest(
                fileContent = "",
                selectionStart = 0,
                selectionEnd = 0
            )
        )
        // The closing tag must not be glued to the user-selection markers when the file is empty.
        assertFalse(
            "Empty file should still place </WalkthroughFileContent> on its own line, got: $result",
            result.contains("</WalkthroughUserSelection></WalkthroughFileContent>")
        )
    }
}
