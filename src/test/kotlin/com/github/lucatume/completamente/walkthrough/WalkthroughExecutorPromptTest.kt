package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.MAX_PROMPT_FILE_CHARS
import com.github.lucatume.completamente.services.PROMPT_FILE_WINDOW_CHARS

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
