package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Order89ExecutorTest : BaseCompletionTest() {

    private fun makeRequest(
        prompt: String = "",
        filePath: String = "/tmp/test.kt",
        fileContent: String = "",
        language: String = "kotlin",
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
        contextChunks: List<ContextChunk> = emptyList()
    ) = Order89Request(
        prompt = prompt,
        filePath = filePath,
        fileContent = fileContent,
        language = language,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        contextChunks = contextChunks
    )

    // -- buildPrompt tests --

    fun testBuildPromptContainsAllSections() {
        val request = makeRequest(
            prompt = "add a method",
            fileContent = "class Foo {}",
            language = "kotlin",
            filePath = "/src/Foo.kt",
            selectionStart = 10,
            selectionEnd = 10
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("<Order89Prompt>"))
        assertTrue(result.contains("</Order89Prompt>"))
        assertTrue(result.contains("<Order89Rules>"))
        assertTrue(result.contains("</Order89Rules>"))
        assertTrue(result.contains("<Order89Context>"))
        assertTrue(result.contains("</Order89Context>"))
        assertTrue(result.contains("<Order89Instruction>"))
        assertTrue(result.contains("add a method"))
        assertTrue(result.contains("</Order89Instruction>"))
        assertTrue(result.contains("<Order89FileContent>"))
        assertTrue(result.contains("</Order89FileContent>"))
        assertTrue(result.contains("Language: kotlin"))
        assertTrue(result.contains("File: /src/Foo.kt"))
        assertTrue(result.contains("REMINDER: Match the file's documentation style."))
    }

    fun testBuildPromptEmptySelection() {
        val request = makeRequest(
            fileContent = "class Foo {}",
            selectionStart = 11,
            selectionEnd = 11
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("class Foo {<Order89UserSelection></Order89UserSelection>}"))
    }

    fun testBuildPromptWithSelection() {
        val request = makeRequest(
            fileContent = "fun main() {\n    println(\"hello\")\n}",
            selectionStart = 17,
            selectionEnd = 33
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("fun main() {\n    <Order89UserSelection>println(\"hello\")</Order89UserSelection>\n}"))
    }

    fun testBuildPromptWithContextChunks() {
        val request = makeRequest(
            prompt = "test",
            fileContent = "code",
            selectionStart = 0,
            selectionEnd = 4,
            contextChunks = listOf(
                ContextChunk("src/Helper.kt", "class Helper { fun help() }"),
                ContextChunk("src/Utils.kt", "object Utils { fun util() }")
            )
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("<Order89ContextFile path=\"src/Helper.kt\">"))
        assertTrue(result.contains("class Helper { fun help() }"))
        assertTrue(result.contains("</Order89ContextFile>"))
        assertTrue(result.contains("<Order89ContextFile path=\"src/Utils.kt\">"))
        assertTrue(result.contains("object Utils { fun util() }"))
    }

    fun testBuildPromptNoContextChunks() {
        val request = makeRequest(
            prompt = "test",
            fileContent = "code",
            selectionStart = 0,
            selectionEnd = 4,
            contextChunks = emptyList()
        )
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("<Order89Context>"))
        assertFalse(result.contains("<Order89ContextFile"))
    }

    fun testBuildPromptRulesContainFenceInstruction() {
        val request = makeRequest(fileContent = "x", selectionStart = 0, selectionEnd = 1)
        val result = Order89Executor.buildPrompt(request)
        assertTrue(result.contains("fenced code block using triple backticks"))
    }

    // -- buildRequestBody tests --

    fun testBuildRequestBodyContainsAllFields() {
        val settings = com.github.lucatume.completamente.services.Settings(
            order89Temperature = 0.7,
            order89TopP = 0.8,
            order89TopK = 20,
            order89RepeatPenalty = 1.05,
            order89NPredict = 1024
        )
        val result = Order89Executor.buildRequestBody("test prompt", settings)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
        assertEquals("test prompt", json["prompt"]!!.jsonPrimitive.content)
        assertEquals("1024", json["n_predict"]!!.jsonPrimitive.content)
        assertEquals(0.7, json["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.8, json["top_p"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("20", json["top_k"]!!.jsonPrimitive.content)
        assertEquals(1.05, json["repeat_penalty"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("false", json["cache_prompt"]!!.jsonPrimitive.content)
        val stopArray = json["stop"]!!.jsonArray
        assertEquals(2, stopArray.size)
        assertEquals("</Order89Prompt>", stopArray[0].jsonPrimitive.content)
        assertEquals("\n\n\n\n", stopArray[1].jsonPrimitive.content)
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

    // -- matchTrailingNewlines tests --

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

    // -- reindentOutput edge cases --

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
        // Unclosed fence falls through to returning the original input
        val input = "```kotlin\nval x = 1"
        val result = Order89Executor.extractCodeBlock(input)
        assertEquals(input, result)
    }

    fun testLooksLikeCodeEmptyString() {
        assertFalse(Order89Executor.looksLikeCode(""))
    }

    // -- buildChatPrompt tests --

    fun testBuildChatPromptPhase1ContainsChatTokens() {
        val request = makeRequest(prompt = "add a method", fileContent = "class Foo {}", selectionStart = 10, selectionEnd = 10)
        val result = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue(result.contains("<|im_start|>system"))
        assertTrue(result.contains("<|im_end|>"))
        assertTrue(result.contains("<|im_start|>user"))
        assertTrue(result.contains("<|im_start|>assistant"))
    }

    fun testBuildChatPromptPhase1ContainsToolSpec() {
        val request = makeRequest(prompt = "add a method", fileContent = "class Foo {}", selectionStart = 10, selectionEnd = 10)
        val result = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue(result.contains("FileSearch"))
        assertTrue(result.contains("DocSearch"))
        assertFalse(result.contains("WebSearch"))
        assertTrue(result.contains("<tool_call>"))
    }

    fun testBuildChatPromptPhase1ContainsToolCallingRule() {
        val request = makeRequest(prompt = "add a method", fileContent = "class Foo {}", selectionStart = 10, selectionEnd = 10)
        val result = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue(result.contains("call the appropriate tool FIRST"))
    }

    fun testBuildChatPromptPhase1ContainsAllSections() {
        val request = makeRequest(
            prompt = "add a method", fileContent = "class Foo {}", language = "kotlin",
            filePath = "/src/Foo.kt", selectionStart = 10, selectionEnd = 10
        )
        val result = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        assertTrue(result.contains("<Order89Rules>"))
        assertTrue(result.contains("<Order89Context>"))
        assertTrue(result.contains("<Order89Instruction>"))
        assertTrue(result.contains("<Order89FileContent>"))
        assertTrue(result.contains("Language: kotlin"))
        assertTrue(result.contains("File: /src/Foo.kt"))
    }

    fun testBuildChatPromptPhase2NoToolSpec() {
        val request = makeRequest(prompt = "add a method", fileContent = "class Foo {}", selectionStart = 10, selectionEnd = 10)
        val result = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = false)
        assertFalse(result.contains("To use a tool, respond with:"))
        assertFalse(result.contains("call the appropriate tool FIRST"))
    }

    fun testBuildChatPromptPhase2ContainsReferenceCode() {
        val request = makeRequest(prompt = "add a method", fileContent = "class Foo {}", selectionStart = 10, selectionEnd = 10)
        val toolResults = listOf(
            ToolResult(
                ToolCall("FileSearch", mapOf("query" to JsonPrimitive("processPayment"))),
                "src/Pay.kt:10: fun processPayment()"
            )
        )
        val result = Order89Executor.buildChatPrompt(request, toolResults, includeTools = false)
        assertTrue(result.contains("<ReferenceCode source=\"FileSearch: processPayment\">"))
        assertTrue(result.contains("src/Pay.kt:10: fun processPayment()"))
        assertTrue(result.contains("</ReferenceCode>"))
    }

    fun testBuildChatPromptPhase2MultipleResults() {
        val request = makeRequest(prompt = "test", fileContent = "code", selectionStart = 0, selectionEnd = 4)
        val toolResults = listOf(
            ToolResult(ToolCall("FileSearch", mapOf("query" to JsonPrimitive("foo"))), "result1"),
            ToolResult(ToolCall("FileSearch", mapOf("query" to JsonPrimitive("bar"))), "result2")
        )
        val result = Order89Executor.buildChatPrompt(request, toolResults, includeTools = false)
        assertTrue(result.contains("<ReferenceCode source=\"FileSearch: foo\">"))
        assertTrue(result.contains("<ReferenceCode source=\"FileSearch: bar\">"))
    }

    fun testBuildChatPromptPhase2EmptyResultsNoReferenceCode() {
        val request = makeRequest(prompt = "test", fileContent = "code", selectionStart = 0, selectionEnd = 4)
        val result = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = false)
        assertFalse(result.contains("<ReferenceCode"))
    }

    fun testBuildChatPromptContextChunksAppearInBothPhases() {
        val chunks = listOf(ContextChunk("src/Helper.kt", "class Helper {}"))
        val request = makeRequest(prompt = "test", fileContent = "code", selectionStart = 0, selectionEnd = 4, contextChunks = chunks)
        val phase1 = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = true)
        val phase2 = Order89Executor.buildChatPrompt(request, emptyList(), includeTools = false)
        assertTrue(phase1.contains("<Order89ContextFile path=\"src/Helper.kt\">"))
        assertTrue(phase2.contains("<Order89ContextFile path=\"src/Helper.kt\">"))
    }

    // -- buildChatRequestBody tests --

    fun testBuildChatRequestBodyHasChatStopSequences() {
        val settings = com.github.lucatume.completamente.services.Settings()
        val result = Order89Executor.buildChatRequestBody("test", settings)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
        val stopArray = json["stop"]!!.jsonArray
        assertEquals(2, stopArray.size)
        assertEquals("<|im_end|>", stopArray[0].jsonPrimitive.content)
        assertEquals("<|im_start|>", stopArray[1].jsonPrimitive.content)
    }

    fun testBuildChatRequestBodyPreservesParameters() {
        val settings = com.github.lucatume.completamente.services.Settings(
            order89Temperature = 0.5,
            order89TopP = 0.9,
            order89TopK = 30
        )
        val result = Order89Executor.buildChatRequestBody("test prompt", settings)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
        assertEquals("test prompt", json["prompt"]!!.jsonPrimitive.content)
        assertEquals(0.5, json["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.9, json["top_p"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("30", json["top_k"]!!.jsonPrimitive.content)
    }
}
