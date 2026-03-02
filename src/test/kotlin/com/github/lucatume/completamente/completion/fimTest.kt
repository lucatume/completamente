package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.DiffEntry

class fimTest : BaseCompletionTest() {

    // --- splitEditIntoSubEdits tests ---

    fun testSplitEditIntoSubEditsPureInsertion() {
        val result = splitEditIntoSubEdits("", "inserted text", 10)
        assertEquals(1, result.size)
        assertEquals(EditRegion(10, 10, "inserted text"), result[0])
    }

    fun testSplitEditIntoSubEditsSingleChange() {
        val old = "line0\nline1\nline2"
        val new = "line0\nmodified\nline2"
        val result = splitEditIntoSubEdits(old, new, 0)
        assertEquals(1, result.size)
        // Character-level refinement: "line1" → "modified"
        val edit = result[0]
        assertEquals("modified", applyEdit(old, edit).lines()[1])
    }

    fun testSplitEditIntoSubEditsMultipleDiscreteChanges() {
        // Renaming ROOT_URL to BASE_URL in two places separated by an unchanged line
        val old = "val url = ROOT_URL + path\nval sep = 1\nprintln(ROOT_URL)"
        val new = "val url = BASE_URL + path\nval sep = 1\nprintln(BASE_URL)"
        val result = splitEditIntoSubEdits(old, new, 0)
        assertEquals(2, result.size)
        // Character-level refinement narrows to just the differing part: ROOT → BASE
        assertEquals("BASE", result[0].newText)
        assertEquals("BASE", result[1].newText)

        // Applying both in order with offset adjustment should produce the expected result
        var text = old
        var edits = result
        for (i in edits.indices) {
            val edit = edits[i]
            text = applyEdit(text, edit)
            if (i < edits.size - 1) {
                edits = advanceSubEdit(edits, i, edit)
            }
        }
        assertEquals(new, text)
    }

    fun testSplitEditIntoSubEditsCharacterLevelRefinement() {
        val old = "val result = x + y"
        val new = "val total = x + y"
        val result = splitEditIntoSubEdits(old, new, 0)
        assertEquals(1, result.size)
        val edit = result[0]
        // Should narrow to just "result" → "total"
        assertEquals("total", edit.newText)
        assertEquals(4, edit.startOffset) // "val " = 4 chars
        assertEquals(10, edit.endOffset) // "val result" = 10 chars, "result" is 4..10
    }

    fun testSplitEditIntoSubEditsDifferentLineCountFallback() {
        val old = "line0\nline1"
        val new = "line0\nnew1\nnew2"
        val result = splitEditIntoSubEdits(old, new, 0)
        // Different line counts: should return single edit covering everything
        assertEquals(1, result.size)
        assertEquals(EditRegion(0, old.length, new), result[0])
    }

    fun testSplitEditIntoSubEditsNoChanges() {
        val text = "line0\nline1\nline2"
        val result = splitEditIntoSubEdits(text, text, 0)
        assertTrue(result.isEmpty())
    }

    fun testSplitEditIntoSubEditsMultiLineGroup() {
        // Two consecutive changed lines should form one group
        val old = "line0\nline1\nline2\nline3"
        val new = "line0\nnewA\nnewB\nline3"
        val result = splitEditIntoSubEdits(old, new, 0)
        assertEquals(1, result.size)
        assertEquals("newA\nnewB", result[0].newText)
    }

    fun testSplitEditIntoSubEditsTwoSeparateGroups() {
        val old = "aaa\nbbb\nccc\nddd"
        val new = "xxx\nbbb\nccc\nyyy"
        val result = splitEditIntoSubEdits(old, new, 0)
        assertEquals(2, result.size)
        // First group: line 0 "aaa" → "xxx"
        assertEquals("xxx", result[0].newText)
        // Second group: line 3 "ddd" → "yyy"
        assertEquals("yyy", result[1].newText)
    }

    fun testSplitEditIntoSubEditsWithBaseOffset() {
        val old = "aaa\nbbb"
        val new = "xxx\nbbb"
        val result = splitEditIntoSubEdits(old, new, 100)
        assertEquals(1, result.size)
        // "aaa" starts at offset 100, character-level refinement
        assertEquals(100, result[0].startOffset)
        assertEquals(103, result[0].endOffset)
        assertEquals("xxx", result[0].newText)
    }

    // --- advanceSubEdit tests ---

    fun testAdvanceSubEditAdjustsOffsetsAfterSmallerReplacement() {
        val edits = listOf(
            EditRegion(10, 18, "short"),  // replaces 8 chars with 5, delta = -3
            EditRegion(30, 38, "other")
        )
        val result = advanceSubEdit(edits, 0, edits[0])
        // First edit unchanged
        assertEquals(edits[0], result[0])
        // Second edit shifted by -3
        assertEquals(EditRegion(27, 35, "other"), result[1])
    }

    fun testAdvanceSubEditAdjustsOffsetsAfterLargerReplacement() {
        val edits = listOf(
            EditRegion(10, 14, "longer_text"),  // replaces 4 chars with 11, delta = +7
            EditRegion(30, 34, "next")
        )
        val result = advanceSubEdit(edits, 0, edits[0])
        assertEquals(EditRegion(37, 41, "next"), result[1])
    }

    fun testAdvanceSubEditPreservesEditsBeforeIndex() {
        val edits = listOf(
            EditRegion(0, 3, "aaa"),
            EditRegion(10, 13, "bbb"),
            EditRegion(20, 23, "ccc")
        )
        val result = advanceSubEdit(edits, 1, edits[1])
        // Edits at index 0 and 1 unchanged
        assertEquals(edits[0], result[0])
        assertEquals(edits[1], result[1])
        // Edit at index 2 unchanged because delta is 0 (3 chars replaced with 3 chars)
        assertEquals(edits[2], result[2])
    }

    fun testAdvanceSubEditWithInsertionDelta() {
        val edits = listOf(
            EditRegion(5, 5, "inserted"),  // pure insertion, delta = +8
            EditRegion(20, 25, "next")
        )
        val result = advanceSubEdit(edits, 0, edits[0])
        assertEquals(EditRegion(28, 33, "next"), result[1])
    }

    // --- buildLineWindowWithStart tests ---

    fun testBuildLineWindowWithStartSmallFile() {
        val lines = listOf("line0", "line1", "line2")
        val (content, startLine) = buildLineWindowWithStart(lines, 1, 10)
        assertEquals("line0\nline1\nline2", content)
        assertEquals(0, startLine)
    }

    fun testBuildLineWindowWithStartLargeFileMiddle() {
        val lines = (0..99).map { "line$it" }
        val (content, startLine) = buildLineWindowWithStart(lines, 50, 10)
        val resultLines = content.lines()
        assertEquals(10, resultLines.size)
        assertTrue(startLine >= 0)
        assertEquals(startLine + 10 - 1 < lines.size, true)
    }

    fun testBuildLineWindowWithStartLargeFileStart() {
        val lines = (0..99).map { "line$it" }
        val (content, startLine) = buildLineWindowWithStart(lines, 0, 10)
        assertEquals(0, startLine)
        assertEquals(10, content.lines().size)
        assertEquals("line0", content.lines().first())
    }

    fun testBuildLineWindowWithStartLargeFileEnd() {
        val lines = (0..99).map { "line$it" }
        val (content, startLine) = buildLineWindowWithStart(lines, 99, 10)
        assertEquals(90, startLine)
        assertEquals(10, content.lines().size)
        assertEquals("line99", content.lines().last())
    }

    fun testBuildLineWindowDelegatesToWithStart() {
        val lines = listOf("a", "b", "c", "d", "e")
        val window = buildLineWindow(lines, 2, 3)
        val (windowFromWithStart, _) = buildLineWindowWithStart(lines, 2, 3)
        assertEquals(windowFromWithStart, window)
    }

    // --- computeWindowStartOffset tests ---

    fun testComputeWindowStartOffsetZero() {
        assertEquals(0, computeWindowStartOffset("line0\nline1\nline2", 0))
    }

    fun testComputeWindowStartOffsetFirstLine() {
        assertEquals(6, computeWindowStartOffset("line0\nline1\nline2", 1))
    }

    fun testComputeWindowStartOffsetSecondLine() {
        assertEquals(12, computeWindowStartOffset("line0\nline1\nline2", 2))
    }

    fun testComputeWindowStartOffsetBeyondEnd() {
        val text = "line0\nline1"
        val offset = computeWindowStartOffset(text, 5)
        assertEquals(text.length, offset)
    }

    // --- computeOffsetInWindow tests ---

    fun testComputeOffsetInWindowZero() {
        assertEquals(0, computeOffsetInWindow("line0\nline1\nline2", 0))
    }

    fun testComputeOffsetInWindowLine1() {
        assertEquals(6, computeOffsetInWindow("line0\nline1\nline2", 1))
    }

    fun testComputeOffsetInWindowLine2() {
        assertEquals(12, computeOffsetInWindow("line0\nline1\nline2", 2))
    }

    fun testComputeOffsetInWindowBeyondEnd() {
        val content = "ab\ncd"
        val offset = computeOffsetInWindow(content, 5)
        assertEquals(content.length, offset)
    }

    // --- extractEdit tests ---

    /**
     * Applies an EditRegion to the given content and returns the result.
     * This is the ground-truth check: applying the edit to the original content
     * must produce the expected updated content.
     */
    private fun applyEdit(content: String, edit: EditRegion): String {
        return content.substring(0, edit.startOffset) + edit.newText + content.substring(edit.endOffset)
    }

    private fun assertEditProduces(current: String, updated: String, result: EditKind) {
        val edit = when (result) {
            is EditKind.Inline -> result.editRegion
            is EditKind.Jump -> result.editRegion
            else -> fail("Expected EditKind.Inline or Jump but got $result") as Nothing
        }
        val applied = applyEdit(current, edit)
        assertEquals(updated.trimEnd(), applied.trimEnd())
    }

    // --- Suppress cases ---

    fun testExtractEditIdenticalContent() {
        val content = "line0\nline1\nline2"
        val result = extractEdit(content, content, 1)
        assertTrue(result is EditKind.Suppress)
    }

    fun testExtractEditEmptyUpdated() {
        val result = extractEdit("line0\nline1", "", 0)
        assertTrue(result is EditKind.Suppress)
    }

    fun testExtractEditWhitespaceOnlyUpdated() {
        val result = extractEdit("line0\nline1", "   \n  \n  ", 0)
        assertTrue(result is EditKind.Suppress)
    }

    fun testExtractEditIdenticalAfterTrim() {
        val content = "line0\nline1"
        val result = extractEdit(content, content + "   \n", 0)
        assertTrue(result is EditKind.Suppress)
    }

    fun testExtractEditFarFromCursorIsJump() {
        val current = "line0\nline1\nline2\nline3\nline4\nline5\nline6"
        val updated = "line0\nline1\nline2\nline3\nline4\nline5\nmodified"
        // cursor at line 0, edit at line 6 (distance > 2) → Jump
        val result = extractEdit(current, updated, 0)
        assertTrue("Expected Jump but got $result", result is EditKind.Jump)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditFarFromCursorIsJumpEditAtLine4() {
        val current = "line0\nline1\nline2\nline3\nline4"
        val updated = "line0\nline1\nline2\nline3\nmodified"
        // cursor at line 0, edit at line 4 (distance = 4, > 2) → Jump
        val result = extractEdit(current, updated, 0)
        assertTrue("Expected Jump but got $result", result is EditKind.Jump)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditExactlyThreeLinesAwayIsJump() {
        val current = "line0\nline1\nline2\nline3"
        val updated = "line0\nline1\nline2\nmodified"
        // cursor at line 0, edit at line 3 (distance = 3, > 2) → Jump
        val result = extractEdit(current, updated, 0)
        assertTrue("Expected Jump but got $result", result is EditKind.Jump)
        assertEditProduces(current, updated, result)
    }

    // --- Replacement: single line ---

    fun testExtractEditReplaceSingleLineFile() {
        val current = "hello"
        val updated = "world"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplaceFirstLineOfMultiLine() {
        val current = "hello\nworld"
        val updated = "hi\nworld"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplaceLastLineOfMultiLine() {
        val current = "hello\nworld"
        val updated = "hello\nearth"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplaceMiddleLine() {
        val current = "line0\nline1\nline2"
        val updated = "line0\nmodified\nline2"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplacementOnCursorLine() {
        val current = "val result = a + b\nreturn result"
        val updated = "val total = a + b\nreturn total"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    // --- Replacement: multiple lines ---

    fun testExtractEditReplaceMultipleLinesInMiddle() {
        val current = "line0\nline1\nline2\nline3\nline4"
        val updated = "line0\nnewA\nnewB\nnewC\nline4"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplaceWithFewerLines() {
        val current = "line0\nline1\nline2\nline3\nline4"
        val updated = "line0\nreplaced\nline4"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplaceWithMoreLines() {
        val current = "line0\nline1\nline4"
        val updated = "line0\nnewA\nnewB\nnewC\nline4"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    // --- Pure insertion ---

    fun testExtractEditInsertInMiddle() {
        val current = "line0\nline3"
        val updated = "line0\nline1\nline2\nline3"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditInsertSingleLineInMiddle() {
        val current = "line0\nline2"
        val updated = "line0\nline1\nline2"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditInsertAtBeginning() {
        val current = "line1\nline2"
        val updated = "line0\nline1\nline2"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditInsertAtEndOfFile() {
        val current = "line0\nline1"
        val updated = "line0\nline1\nline2\nline3"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditInsertSingleLineAtEnd() {
        val current = "line0"
        val updated = "line0\nline1"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditPureInsertionAtCursor() {
        val current = "function isEven(\n\nreturn true"
        val updated = "function isEven(\n    n: Int\n): Boolean {\n\nreturn true"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    // --- Pure deletion ---

    fun testExtractEditDeleteMiddleLines() {
        val current = "line0\nline1\nline2\nline3"
        val updated = "line0\nline3"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditDeleteSingleMiddleLine() {
        val current = "line0\nline1\nline2"
        val updated = "line0\nline2"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditDeleteLastLine() {
        val current = "line0\nline1"
        val updated = "line0"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    // --- Near-cursor boundary (distance exactly 2) ---

    fun testExtractEditNearCursorWithinTwoLines() {
        val current = "line0\nline1\nline2\nline3"
        val updated = "line0\nline1\nmodified\nline3"
        // cursor at line 1, edit at line 2 (distance = 1, within 2)
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditExactlyTwoLinesAwayIsAllowed() {
        val current = "line0\nline1\nline2\nline3"
        val updated = "line0\nline1\nmodified\nline3"
        // cursor at line 0, edit at line 2 (distance = 2, exactly the boundary)
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    // --- FIM completion scenarios ---

    fun testExtractEditFimCompletionSingleLine() {
        val current = "function isEven("
        val updated = "function isEven(n: number): boolean {\n    return n % 2 === 0;\n}"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditFimCompletionAppendBody() {
        val current = "fun greet(name: String) {"
        val updated = "fun greet(name: String) {\n    println(\"Hello, \$name\")\n}"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    // --- Cursor line extension with whitespace mismatch ---

    fun testExtractEditCursorLineExtensionWithWhitespaceMismatch() {
        // User typed with spaces, model returns tabs. The edit should be a suffix insertion.
        val current = "    public function createPluginsDirectory\n\n\t/**"
        val updated = "\tpublic function createPluginsDirectory(): self {\n\t\treturn \$this->create();\n\t}\n\n\t/**"
        val cursorLineInWindow = 0
        val result = extractEdit(current, updated, cursorLineInWindow)
        assertTrue("Expected Inline but got $result", result is EditKind.Inline)
        val edit = (result as EditKind.Inline).editRegion
        // The edit should start at the end of the current cursor line, not replace it
        val cursorLineEnd = "    public function createPluginsDirectory".length
        assertEquals(cursorLineEnd, edit.startOffset)
        // The newText should be just the suffix + new lines, not the full replaced line
        assertTrue("newText should start with the suffix", edit.newText.startsWith("(): self {"))
        assertFalse("newText should not contain the original method name prefix", edit.newText.contains("public function createPluginsDirectory():"))
        // Applying the edit should produce correct content
        val applied = applyEdit(current, edit)
        assertEquals(
            "    public function createPluginsDirectory(): self {\n\t\treturn \$this->create();\n\t}\n\n\t/**",
            applied.trimEnd()
        )
    }

    fun testExtractEditCursorLineExtensionSameWhitespace() {
        // Same whitespace - should also produce suffix insertion
        val current = "\tpublic function createPluginsDirectory\n\n\t/**"
        val updated = "\tpublic function createPluginsDirectory(): self {\n\t\treturn \$this->create();\n\t}\n\n\t/**"
        val result = extractEdit(current, updated, 0)
        assertTrue("Expected Inline but got $result", result is EditKind.Inline)
        val edit = (result as EditKind.Inline).editRegion
        assertTrue("newText should start with suffix", edit.newText.startsWith("(): self {"))
    }

    // --- NEP (next-edit prediction) scenarios ---

    fun testExtractEditNepRenameVariable() {
        val current = "val result = x + y\nprintln(result)"
        val updated = "val total = x + y\nprintln(total)"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditNepAddCatchBlock() {
        val current = "try {\n    doSomething()\n}"
        val updated = "try {\n    doSomething()\n} catch (e: Exception) {\n    log(e)\n}"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    // --- Real-world NEP scenario: constructor completion in PHP ---

    fun testExtractEditNepConstructorCompletionInPhpClass() {
        // Exact file content when the user requested completion.
        // Cursor is on line 5 (0-indexed): "    public function __con"
        val currentContent = """<?php

namespace StellarWP\Slic\Test\Support\Factories;

class PluginsDirectory {
    public function __con

    public static function createTemp( ?string ${'$'}path = null ): PluginsDirectory {
        ${'$'}path ??= '/slic-test-plugins-dir-' . uniqid( '', true );
        ${'$'}dir  = sys_get_temp_dir() . '/' . ltrim( ${'$'}path, '/' );

        if ( is_dir( ${'$'}dir ) ) {
            throw new \RuntimeException( "Directory ${'$'}dir already exists." );
        }

        if ( ! mkdir( ${'$'}dir, 0777, true ) || ! is_dir( ${'$'}dir ) ) {
            throw new \RuntimeException( "Failed to create plugins directory ${'$'}dir" );
        }

        return new self(${'$'}dir);
    }
}"""

        // Exact server response (starts with \n because prompt ends with path line).
        val serverResponse = """
<?php

namespace StellarWP\Slic\Test\Support\Factories;

class PluginsDirectory {
    public function __construct( private string ${'$'}path ) {
    }

    public static function createTemp( ?string ${'$'}path = null ): PluginsDirectory {
        ${'$'}path ??= '/slic-test-plugins-dir-' . uniqid( '', true );
        ${'$'}dir  = sys_get_temp_dir() . '/' . ltrim( ${'$'}path, '/' );

        if ( is_dir( ${'$'}dir ) ) {
            throw new \RuntimeException( "Directory ${'$'}dir already exists." );
        }

        if ( ! mkdir( ${'$'}dir, 0777, true ) || ! is_dir( ${'$'}dir ) ) {
            throw new \RuntimeException( "Failed to create plugins directory ${'$'}dir" );
        }

        return new self(${'$'}dir);
    }
}"""

        // The file is < 60 lines so the whole file is the window. Cursor is on line 5.
        val cursorLineInWindow = 5
        val result = extractEdit(currentContent, serverResponse, cursorLineInWindow)

        // Should be an Inline edit (near cursor), not Suppress
        assertTrue("Expected Inline but got $result", result is EditKind.Inline)
        val edit = (result as EditKind.Inline).editRegion

        // The edit should only affect the constructor area (line 5 and the empty line 6),
        // replacing them with the completed constructor + closing brace + empty line.
        // It must NOT replace the rest of the file with identical content.
        val applied = applyEdit(currentContent, edit)
        val expectedAfterEdit = """<?php

namespace StellarWP\Slic\Test\Support\Factories;

class PluginsDirectory {
    public function __construct( private string ${'$'}path ) {
    }

    public static function createTemp( ?string ${'$'}path = null ): PluginsDirectory {
        ${'$'}path ??= '/slic-test-plugins-dir-' . uniqid( '', true );
        ${'$'}dir  = sys_get_temp_dir() . '/' . ltrim( ${'$'}path, '/' );

        if ( is_dir( ${'$'}dir ) ) {
            throw new \RuntimeException( "Directory ${'$'}dir already exists." );
        }

        if ( ! mkdir( ${'$'}dir, 0777, true ) || ! is_dir( ${'$'}dir ) ) {
            throw new \RuntimeException( "Failed to create plugins directory ${'$'}dir" );
        }

        return new self(${'$'}dir);
    }
}"""
        assertEquals(expectedAfterEdit.trimEnd(), applied.trimEnd())

        // Critical: the newText should only contain the constructor completion suffix,
        // not the entire rest of the file.
        assertFalse(
            "newText should not contain createTemp - it should only have the constructor completion",
            edit.newText.contains("createTemp")
        )
        assertTrue(
            "newText should contain the constructor completion suffix",
            edit.newText.contains("struct(")
        )
    }

    /**
     * Same scenario as above but with a trailing newline in the current content,
     * which is what IntelliJ's document.text typically produces.
     * The server response is trimmed by extractEdit, so it has no trailing newline.
     * This mismatch can break the bottom-scan diff.
     */
    fun testExtractEditNepConstructorCompletionTrailingNewline() {
        // Current content ends with \n (as IntelliJ documents typically do)
        val currentContent = "<?php\n" +
            "\n" +
            "namespace StellarWP\\Slic\\Test\\Support\\Factories;\n" +
            "\n" +
            "class PluginsDirectory {\n" +
            "    public function __con\n" +
            "\n" +
            "    public static function createTemp( ?string \$path = null ): PluginsDirectory {\n" +
            "        \$path ??= '/slic-test-plugins-dir-' . uniqid( '', true );\n" +
            "        \$dir  = sys_get_temp_dir() . '/' . ltrim( \$path, '/' );\n" +
            "\n" +
            "        if ( is_dir( \$dir ) ) {\n" +
            "            throw new \\RuntimeException( \"Directory \$dir already exists.\" );\n" +
            "        }\n" +
            "\n" +
            "        if ( ! mkdir( \$dir, 0777, true ) || ! is_dir( \$dir ) ) {\n" +
            "            throw new \\RuntimeException( \"Failed to create plugins directory \$dir\" );\n" +
            "        }\n" +
            "\n" +
            "        return new self(\$dir);\n" +
            "    }\n" +
            "}\n"

        // Server response (starts with \n, no trailing newline after trim)
        val serverResponse = "\n" +
            "<?php\n" +
            "\n" +
            "namespace StellarWP\\Slic\\Test\\Support\\Factories;\n" +
            "\n" +
            "class PluginsDirectory {\n" +
            "    public function __construct( private string \$path ) {\n" +
            "    }\n" +
            "\n" +
            "    public static function createTemp( ?string \$path = null ): PluginsDirectory {\n" +
            "        \$path ??= '/slic-test-plugins-dir-' . uniqid( '', true );\n" +
            "        \$dir  = sys_get_temp_dir() . '/' . ltrim( \$path, '/' );\n" +
            "\n" +
            "        if ( is_dir( \$dir ) ) {\n" +
            "            throw new \\RuntimeException( \"Directory \$dir already exists.\" );\n" +
            "        }\n" +
            "\n" +
            "        if ( ! mkdir( \$dir, 0777, true ) || ! is_dir( \$dir ) ) {\n" +
            "            throw new \\RuntimeException( \"Failed to create plugins directory \$dir\" );\n" +
            "        }\n" +
            "\n" +
            "        return new self(\$dir);\n" +
            "    }\n" +
            "}"

        val cursorLineInWindow = 5
        val result = extractEdit(currentContent, serverResponse, cursorLineInWindow)

        assertTrue("Expected Inline but got $result", result is EditKind.Inline)
        val edit = (result as EditKind.Inline).editRegion

        // The edit should only affect the constructor area, NOT the rest of the file.
        assertFalse(
            "newText should not contain createTemp - it should only have the constructor",
            edit.newText.contains("createTemp")
        )
        assertTrue(
            "newText should contain the constructor completion suffix",
            edit.newText.contains("struct(")
        )
    }

    // --- Edge cases with empty lines ---

    fun testExtractEditInsertEmptyLineInMiddle() {
        val current = "line0\nline1"
        val updated = "line0\n\nline1"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, updated, result)
    }

    fun testExtractEditReplaceLineWithEmptyLine() {
        val current = "line0\nline1\nline2"
        val updated = "line0\n\nline2"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, updated, result)
    }

    // --- Leading newline normalization (model response artifact) ---

    fun testExtractEditLeadingNewlineInUpdatedContent() {
        val current = "line0\nline1\nline2"
        // Model response starts with \n because prompt ends with path line
        val updated = "\nline0\nmodified\nline2"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, "line0\nmodified\nline2", result)
    }

    fun testExtractEditLeadingNewlineWithInsertion() {
        val current = "line0\nline1"
        val updated = "\nline0\nnew_line\nline1"
        val result = extractEdit(current, updated, 0)
        assertEditProduces(current, "line0\nnew_line\nline1", result)
    }

    fun testExtractEditLeadingNewlineIdenticalContentIsSuppressed() {
        val current = "line0\nline1"
        val updated = "\nline0\nline1"
        val result = extractEdit(current, updated, 0)
        assertTrue("Expected Suppress but got $result", result is EditKind.Suppress)
    }

    fun testExtractEditMultipleLeadingNewlines() {
        val current = "line0\nline1\nline2"
        val updated = "\n\n\nline0\nmodified\nline2"
        val result = extractEdit(current, updated, 1)
        assertEditProduces(current, "line0\nmodified\nline2", result)
    }

    // --- Offset calculation correctness ---

    fun testOffsetCalculationWithEmptyLines() {
        val content = "a\n\nb\n\nc"
        assertEquals(0, computeOffsetInWindow(content, 0))
        assertEquals(2, computeOffsetInWindow(content, 1)) // after "a\n"
        assertEquals(3, computeOffsetInWindow(content, 2)) // after "a\n\n"
    }

    fun testOffsetCalculationSingleLine() {
        val content = "hello world"
        assertEquals(0, computeOffsetInWindow(content, 0))
        assertEquals(content.length, computeOffsetInWindow(content, 1)) // no newline, goes to end
    }

    // --- buildFimRequest tests ---

    fun testBuildFimRequestPopulatesWindowFields() {
        val content = (0..99).joinToString("\n") { "line$it" }
        val request = buildFimRequest("test.kt", content, 50)
        assertTrue(request.windowedContent.isNotEmpty())
        assertTrue(request.windowStartLine >= 0)
        assertTrue(request.windowedContent.lines().size <= MAX_FILE_CHUNK_LINES)
    }

    fun testBuildFimRequestSmallFile() {
        val content = "line0\nline1\nline2"
        val request = buildFimRequest("test.kt", content, 1)
        assertEquals(content, request.windowedContent)
        assertEquals(0, request.windowStartLine)
    }

    fun testBuildFimRequestWithChunks() {
        val chunks = listOf(Chunk("chunk text", 1000, "file.kt"))
        val request = buildFimRequest("test.kt", "content", 0, chunks = chunks)
        assertEquals(chunks, request.chunks)
    }

    // --- truncateDiffText tests ---

    fun testTruncateDiffTextUnderLimit() {
        val text = "short text"
        assertEquals(text, truncateDiffText(text))
    }

    fun testTruncateDiffTextAtLimit() {
        val text = "a".repeat(MAX_DIFF_CHARS)
        assertEquals(text, truncateDiffText(text))
    }

    fun testTruncateDiffTextOverLimit() {
        val text = "a".repeat(MAX_DIFF_CHARS + 100)
        val result = truncateDiffText(text)
        assertEquals(MAX_DIFF_CHARS + "...[truncated]".length, result.length)
        assertTrue(result.endsWith("...[truncated]"))
        assertTrue(result.startsWith("a".repeat(MAX_DIFF_CHARS)))
    }

    // --- buildFimPrompt with chunks tests ---

    fun testBuildFimPromptNoChunks() {
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            chunks = emptyList()
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("<|file_sep|>original/test.kt"))
        assertTrue(prompt.contains("<|file_sep|>current/test.kt"))
        assertTrue(prompt.contains("<|file_sep|>updated/test.kt"))
    }

    fun testBuildFimPromptSingleChunk() {
        val chunks = listOf(Chunk("chunk content", 1000, "buffer.kt"))
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            chunks = chunks
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("<|file_sep|>buffer.kt"))
        assertTrue(prompt.contains("chunk content"))
    }

    fun testBuildFimPromptMultipleChunks() {
        val chunks = listOf(
            Chunk("text A", 1000, "a.kt"),
            Chunk("text B", 2000, "b.kt")
        )
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            chunks = chunks
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("<|file_sep|>a.kt"))
        assertTrue(prompt.contains("text A"))
        assertTrue(prompt.contains("<|file_sep|>b.kt"))
        assertTrue(prompt.contains("text B"))
        val aIndex = prompt.indexOf("<|file_sep|>a.kt")
        val bIndex = prompt.indexOf("<|file_sep|>b.kt")
        assertTrue("First chunk should appear before second", aIndex < bIndex)
    }

    fun testBuildFimPromptChunksBeforeDiffs() {
        val chunks = listOf(Chunk("chunk text", 1000, "chunk.kt"))
        val diffs = listOf(DiffEntry("diff.kt", "old", "new"))
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs,
            chunks = chunks
        )
        val prompt = buildFimPrompt(request)
        val chunkIndex = prompt.indexOf("<|file_sep|>chunk.kt")
        val diffIndex = prompt.indexOf("<|file_sep|>diff.kt.diff")
        val originalIndex = prompt.indexOf("<|file_sep|>original/test.kt")
        assertTrue("Chunks should appear before diffs", chunkIndex < diffIndex)
        assertTrue("Diffs should appear before original", diffIndex < originalIndex)
    }

    // --- buildFimPrompt diff truncation tests ---

    fun testBuildFimPromptTruncatesLongDiffOriginal() {
        val longOriginal = "x".repeat(MAX_DIFF_CHARS + 500)
        val truncatedOriginal = truncateDiffText(longOriginal)
        val diffs = listOf(DiffEntry("file.kt", truncatedOriginal, "short"))
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("...[truncated]"))
        assertFalse(prompt.contains(longOriginal))
    }

    fun testBuildFimPromptTruncatesLongDiffUpdated() {
        val longUpdated = "y".repeat(MAX_DIFF_CHARS + 500)
        val truncatedUpdated = truncateDiffText(longUpdated)
        val diffs = listOf(DiffEntry("file.kt", "short", truncatedUpdated))
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("...[truncated]"))
        assertFalse(prompt.contains(longUpdated))
    }

    // --- Definition chunks in prompt tests ---

    fun testBuildFimPromptDefinitionChunksAppearBeforeRingChunks() {
        val defChunks = listOf(Chunk("def content", 1000, "definitions/Helper.kt"))
        val ringChunks = listOf(Chunk("ring content", 2000, "ring/Buffer.kt"))
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            definitionChunks = defChunks,
            chunks = ringChunks
        )
        val prompt = buildFimPrompt(request)
        val defIndex = prompt.indexOf("<|file_sep|>definitions/Helper.kt")
        val ringIndex = prompt.indexOf("<|file_sep|>ring/Buffer.kt")
        val originalIndex = prompt.indexOf("<|file_sep|>original/test.kt")
        assertTrue("Definition chunks should be present", defIndex >= 0)
        assertTrue("Ring chunks should be present", ringIndex >= 0)
        assertTrue("Definition chunks should appear before ring chunks", defIndex < ringIndex)
        assertTrue("Ring chunks should appear before original", ringIndex < originalIndex)
        assertTrue("Prompt should contain def content", prompt.contains("def content"))
        assertTrue("Prompt should contain ring content", prompt.contains("ring content"))
    }

    fun testBuildFimPromptEmptyDefinitionChunksBackwardCompatible() {
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            chunks = listOf(Chunk("chunk text", 1000, "chunk.kt"))
        )
        val prompt = buildFimPrompt(request)
        // Should produce same structure as before: chunk → original → current → updated
        assertTrue(prompt.contains("<|file_sep|>chunk.kt"))
        assertTrue(prompt.contains("<|file_sep|>original/test.kt"))
        assertTrue(prompt.contains("<|file_sep|>current/test.kt"))
        assertTrue(prompt.contains("<|file_sep|>updated/test.kt"))
        // No definition separator should appear
        val firstSep = prompt.indexOf("<|file_sep|>")
        assertEquals("First separator should be the chunk", "<|file_sep|>chunk.kt", prompt.substring(firstSep, firstSep + "<|file_sep|>chunk.kt".length))
    }

    fun testBuildFimPromptDoesNotTruncateShortDiffs() {
        val diffs = listOf(DiffEntry("file.kt", "original text", "updated text"))
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("original text"))
        assertTrue(prompt.contains("updated text"))
        assertFalse(prompt.contains("...[truncated]"))
    }
}
