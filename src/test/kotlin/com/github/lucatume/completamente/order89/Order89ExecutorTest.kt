package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import java.io.File

class Order89ExecutorTest : BaseCompletionTest() {

    private fun makeRequest(
        commandTemplate: String = "echo test",
        prompt: String = "",
        filePath: String = "/tmp/test.kt",
        fileContent: String = "",
        language: String = "kotlin",
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
        workingDirectory: String = "/tmp"
    ) = Order89Request(
        commandTemplate = commandTemplate,
        prompt = prompt,
        filePath = filePath,
        fileContent = fileContent,
        language = language,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        workingDirectory = workingDirectory
    )

    // -- buildPromptFile tests --

    fun testBuildPromptFileSelectionInMiddle() {
        val content = "fun main() {\n    println(\"hello\")\n}"
        val request = makeRequest(
            prompt = "make it print world",
            fileContent = content,
            language = "kotlin",
            filePath = "/tmp/test.kt",
            selectionStart = 17,
            selectionEnd = 33
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("fun main() {\n    <Order89UserSelection>println(\"hello\")</Order89UserSelection>\n}"))
        } finally {
            File(path).delete()
        }
    }

    fun testBuildPromptFileEmptySelection() {
        val content = "fun main() {}"
        val request = makeRequest(
            prompt = "add a println",
            fileContent = content,
            selectionStart = 12,
            selectionEnd = 12
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("fun main() {<Order89UserSelection></Order89UserSelection>}"))
        } finally {
            File(path).delete()
        }
    }

    fun testBuildPromptFileSelectionAtStart() {
        val content = "fun main() {}"
        val request = makeRequest(
            prompt = "rename function",
            fileContent = content,
            selectionStart = 0,
            selectionEnd = 3
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("<Order89UserSelection>fun</Order89UserSelection> main() {}"))
        } finally {
            File(path).delete()
        }
    }

    fun testBuildPromptFileSelectionAtEnd() {
        val content = "fun main() {}"
        val request = makeRequest(
            prompt = "change closing",
            fileContent = content,
            selectionStart = 12,
            selectionEnd = 13
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("fun main() {<Order89UserSelection>}</Order89UserSelection>"))
        } finally {
            File(path).delete()
        }
    }

    fun testBuildPromptFileSelectionSpansEntireFile() {
        val content = "fun main() {}"
        val request = makeRequest(
            prompt = "rewrite everything",
            fileContent = content,
            selectionStart = 0,
            selectionEnd = content.length
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("<Order89UserSelection>fun main() {}</Order89UserSelection>"))
        } finally {
            File(path).delete()
        }
    }

    fun testBuildPromptFileContainsInstructionLanguageAndPath() {
        val request = makeRequest(
            prompt = "do something",
            fileContent = "code",
            language = "java",
            filePath = "/home/user/Main.java",
            selectionStart = 0,
            selectionEnd = 4
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("<Order89Instruction>"))
            assertTrue(result.contains("do something"))
            assertTrue(result.contains("</Order89Instruction>"))
            assertTrue(result.contains("Language: java"))
            assertTrue(result.contains("File: /home/user/Main.java"))
        } finally {
            File(path).delete()
        }
    }

    fun testBuildPromptFileCreatesReadableFile() {
        val request = makeRequest(
            prompt = "test",
            fileContent = "hello",
            selectionStart = 0,
            selectionEnd = 5
        )

        val path = Order89Executor.buildPromptFile(request)
        try {
            val file = File(path)
            assertTrue(file.exists())
            assertTrue(file.canRead())
            assertTrue(file.length() > 0)
        } finally {
            File(path).delete()
        }
    }

    // -- reindentOutput tests --

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

    // -- detectBaseIndent tests --

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

    // -- buildCommand tests --

    fun testBuildCommandSubstitutesPromptFile() {
        val request = makeRequest(
            commandTemplate = "claude --prompt-file {{prompt_file}}"
        )

        val result = Order89Executor.buildCommand(request, "/tmp/order89-abc.txt")

        assertEquals("claude --prompt-file /tmp/order89-abc.txt", result)
    }

    fun testBuildCommandWithNoPlaceholders() {
        val request = makeRequest(
            commandTemplate = "echo hello world"
        )

        val result = Order89Executor.buildCommand(request, "/tmp/order89-abc.txt")

        assertEquals("echo hello world", result)
    }

    // -- execute tests --

    fun testExecuteWithEchoCommand() {
        val request = makeRequest(
            commandTemplate = "echo 'val x = 1'",
            workingDirectory = "/tmp"
        )

        val (process, future) = Order89Executor.execute(request)
        val result = future.get()

        assertTrue(result.success)
        assertEquals("val x = 1", result.output)
        assertEquals(0, result.exitCode)
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

    fun testExecuteStripsCodeFencesOnSuccess() {
        val request = makeRequest(
            commandTemplate = "printf '```kotlin\\nval x = 1\\n```'",
            workingDirectory = "/tmp"
        )
        val (_, future) = Order89Executor.execute(request)
        val result = future.get()
        assertTrue(result.success)
        assertEquals("val x = 1", result.output)
    }

    fun testExecuteWithCommandThatProducesStderr() {
        val request = makeRequest(
            commandTemplate = "echo error >&2 && exit 1",
            workingDirectory = "/tmp"
        )

        val (process, future) = Order89Executor.execute(request)
        val result = future.get()

        assertFalse(result.success)
        assertTrue(result.output.contains("error"))
    }

    fun testBuildPromptFileContainsV3PromptElements() {
        val request = makeRequest(prompt = "test", fileContent = "code", selectionStart = 0, selectionEnd = 4)
        val path = Order89Executor.buildPromptFile(request)
        try {
            val result = File(path).readText()
            assertTrue(result.contains("You are a code transformation tool"))
            assertTrue(result.contains("<Order89Rules>"))
            assertTrue(result.contains("</Order89Rules>"))
            assertTrue(result.contains("Do NOT describe what you are about to do"))
            assertTrue(result.contains("valid in the target language"))
        } finally {
            File(path).delete()
        }
    }

    // -- truncatePrompt tests --

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

    // -- extractCodeBlock tests --

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

    // -- looksLikeCode tests --

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

    // -- stripLeadingProse tests --

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

    fun testStripLeadingProseWithIndentedCode() {
        val input = "Here it is:\n    \$x = 1;"
        assertEquals("    \$x = 1;", Order89Executor.stripLeadingProse(input))
    }

    fun testStripLeadingProseWithComment() {
        val input = "I'll write this:\n// comment\ncode()"
        assertEquals("// comment\ncode()", Order89Executor.stripLeadingProse(input))
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

    // -- stripTrailingProse tests --

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

    // -- cleanOutput integration tests --

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
}
