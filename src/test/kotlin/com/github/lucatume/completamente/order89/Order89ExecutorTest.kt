package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest

class Order89ExecutorTest : BaseCompletionTest() {

    private fun makeRequest(
        commandTemplate: String = "echo test",
        prompt: String = "",
        selectedText: String = "",
        filePath: String = "/tmp/test.kt",
        fileContent: String = "",
        language: String = "kotlin",
        referencedFiles: List<String> = emptyList(),
        workingDirectory: String = "/tmp"
    ) = Order89Request(
        commandTemplate = commandTemplate,
        prompt = prompt,
        selectedText = selectedText,
        filePath = filePath,
        fileContent = fileContent,
        language = language,
        referencedFiles = referencedFiles,
        workingDirectory = workingDirectory
    )

    fun testBuildCommandSubstitutesAllPlaceholders() {
        val request = makeRequest(
            commandTemplate = "tool --prompt {{prompt}} --sel {{selected_text}} --file {{file_path}} --content {{file_content}} --lang {{language}} --refs {{referenced_files}}",
            prompt = "do stuff",
            selectedText = "some code",
            filePath = "/home/user/file.kt",
            fileContent = "fun main() {}",
            language = "kotlin",
            referencedFiles = listOf("/a.kt", "/b.kt")
        )

        val result = Order89Executor.buildCommand(request)

        assertFalse(result.contains("{{prompt}}"))
        assertFalse(result.contains("{{selected_text}}"))
        assertFalse(result.contains("{{file_path}}"))
        assertFalse(result.contains("{{file_content}}"))
        assertFalse(result.contains("{{language}}"))
        assertFalse(result.contains("{{referenced_files}}"))

        assertTrue(result.contains("'/home/user/file.kt'"))
        assertTrue(result.contains("'kotlin'"))
        assertTrue(result.contains("'/a.kt\n/b.kt'"))
    }

    fun testShellEscapeHandlesSingleQuotes() {
        val result = Order89Executor.shellEscape("it's")
        assertEquals("'it'\\''s'", result)
    }

    fun testShellEscapeHandlesNewlines() {
        val result = Order89Executor.shellEscape("line1\nline2")
        assertEquals("'line1\nline2'", result)
    }

    fun testShellEscapeHandlesEmptyString() {
        val result = Order89Executor.shellEscape("")
        assertEquals("''", result)
    }

    fun testBuildCommandShellEscapesPromptAndSelectedText() {
        val request = makeRequest(
            commandTemplate = "tool {{prompt}} {{selected_text}}",
            prompt = "it's a test",
            selectedText = "don't stop"
        )

        val result = Order89Executor.buildCommand(request)

        assertTrue(result.contains("'it'\\''s a test'"))
        assertTrue(result.contains("'don'\\''t stop'"))
    }

    fun testExecuteWithEchoCommand() {
        val request = makeRequest(
            commandTemplate = "echo hello",
            workingDirectory = "/tmp"
        )

        val (process, future) = Order89Executor.execute(request)
        val result = future.get()

        assertTrue(result.success)
        assertEquals("hello\n", result.output)
        assertEquals(0, result.exitCode)
    }

    fun testBuildCommandWithEmptySelectedText() {
        val request = makeRequest(
            commandTemplate = "tool --sel {{selected_text}}",
            selectedText = ""
        )

        val result = Order89Executor.buildCommand(request)

        assertTrue(result.contains("''"))
    }

    fun testBuildCommandWithMultilinePrompt() {
        val request = makeRequest(
            commandTemplate = "tool --prompt {{prompt}}",
            prompt = "line1\nline2\nline3"
        )

        val result = Order89Executor.buildCommand(request)

        assertTrue(result.contains("'line1\nline2\nline3'"))
    }

    fun testBuildCommandWithSpecialCharsInFilePath() {
        val request = makeRequest(
            commandTemplate = "tool --file {{file_path}}",
            filePath = "/home/user/my project/file (1).kt"
        )

        val result = Order89Executor.buildCommand(request)

        assertTrue(result.contains("'/home/user/my project/file (1).kt'"))
    }

    fun testBuildCommandShellEscapesFileContent() {
        val request = makeRequest(
            commandTemplate = "tool --content {{file_content}}",
            fileContent = "val s = 'hello'"
        )

        val result = Order89Executor.buildCommand(request)

        assertTrue(result.contains("'val s = '\\''hello'\\'''"))
    }

    fun testBuildCommandWithEmptyReferencedFiles() {
        val request = makeRequest(
            commandTemplate = "tool --refs {{referenced_files}}",
            referencedFiles = emptyList()
        )

        val result = Order89Executor.buildCommand(request)

        assertEquals("tool --refs ''", result)
    }

    fun testBuildCommandWithNoPlaceholders() {
        val request = makeRequest(
            commandTemplate = "echo hello world"
        )

        val result = Order89Executor.buildCommand(request)

        assertEquals("echo hello world", result)
    }

    fun testShellEscapeHandlesUnicode() {
        val result = Order89Executor.shellEscape("héllo wörld")
        assertEquals("'héllo wörld'", result)
    }

    fun testShellEscapeHandlesMultipleSingleQuotes() {
        val result = Order89Executor.shellEscape("it's Bob's")
        assertEquals("'it'\\''s Bob'\\''s'", result)
    }

    fun testShellEscapeHandlesSpecialShellChars() {
        val result = Order89Executor.shellEscape("\$`!\"\\")
        assertEquals("'\$`!\"\\'", result)
    }

    fun testExecuteWithFailingCommand() {
        val request = makeRequest(
            commandTemplate = "exit 1",
            workingDirectory = "/tmp"
        )

        val (process, future) = Order89Executor.execute(request)
        val result = future.get()

        assertFalse(result.success)
        assertEquals(1, result.exitCode)
    }

    fun testExecuteWithCommandThatProducesStderr() {
        val request = makeRequest(
            commandTemplate = "echo error >&2",
            workingDirectory = "/tmp"
        )

        val (process, future) = Order89Executor.execute(request)
        val result = future.get()

        assertTrue(result.output.contains("error"))
    }
}
