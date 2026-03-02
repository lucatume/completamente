package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class DefinitionsTest : BaseCompletionTest() {

    // --- resolveDefinitionChunks integration tests ---
    // Note: the light test platform doesn't bundle the Java plugin, so Java PSI
    // references won't resolve. These tests verify safe behavior (no exceptions,
    // empty result for unresolvable references). The core merge/extract logic
    // is tested thoroughly via the pure function tests below.

    fun testResolveDefinitionChunksReturnsEmptyForUnresolvableReferences() {
        val psiFile = myFixture.configureByText(
            "Unresolvable.java",
            """
            public class Unresolvable {
                void run() {
                    NonExistentClass.missing();
                }
            }
            """.trimIndent()
        )

        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        // Place cursor at "NonExistentClass"
        val cursorOffset = psiFile.text.indexOf("NonExistentClass")
        val chunks = resolveDefinitionChunks(project, psiFile, cursorOffset, currentPath)

        assertTrue("Unresolvable references should produce no chunks", chunks.isEmpty())
    }

    fun testResolveDefinitionChunksNoExceptionOnEmptyFile() {
        val psiFile = myFixture.configureByText("Empty.java", "")
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val chunks = resolveDefinitionChunks(project, psiFile, 0, currentPath)

        assertTrue("Empty file should produce no chunks", chunks.isEmpty())
    }

    fun testResolveDefinitionChunksNoExceptionOnPlainText() {
        val psiFile = myFixture.configureByText(
            "plain.txt",
            "This is just plain text with no references at all."
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val chunks = resolveDefinitionChunks(project, psiFile, 0, currentPath)

        assertTrue("Plain text should produce no chunks", chunks.isEmpty())
    }

    fun testResolveDefinitionChunksAtMidFile() {
        val psiFile = myFixture.configureByText(
            "MidFile.java",
            """
            public class MidFile {
                void a() {}
                void b() {}
                void c() {}
            }
            """.trimIndent()
        )

        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        // Place cursor at "b" method name
        val cursorOffset = psiFile.text.indexOf("void b")
        val chunks = resolveDefinitionChunks(project, psiFile, cursorOffset, currentPath)

        // No cross-file references in a self-contained class in light test
        assertTrue("Self-contained class should produce no cross-file chunks", chunks.isEmpty())
    }

    // --- mergeAndExtractDefinitionChunks tests (pure function) ---

    fun testMergeEmptyLocations() {
        val chunks = mergeAndExtractDefinitionChunks(emptyList())
        assertTrue("Empty locations should produce no chunks", chunks.isEmpty())
    }

    fun testMergeSingleLocation() {
        val lines = (0 until 30).map { "// line $it" }
        val docText = lines.joinToString("\n")
        val locations = listOf(DefinitionLocation("File.java", 15, docText, 30))

        val chunks = mergeAndExtractDefinitionChunks(locations)
        assertEquals(1, chunks.size)
        assertEquals("File.java", chunks[0].filename)
        // Window: lines 6..24 (15-9=6, 15+9=24)
        val chunkLines = chunks[0].text.lines()
        assertEquals(19, chunkLines.size)
        assertTrue(chunks[0].text.contains("// line 15"))
        assertTrue(chunks[0].text.contains("// line 6"))
        assertTrue(chunks[0].text.contains("// line 24"))
    }

    fun testMergeOverlappingWindows() {
        // Two definitions in the same file within 18 lines → should merge into 1 chunk
        val lines = (0 until 60).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = listOf(
            DefinitionLocation("Db.java", 20, docText, 60),
            DefinitionLocation("Db.java", 28, docText, 60)  // within 18 lines
        )

        val chunks = mergeAndExtractDefinitionChunks(locations)
        val dbChunks = chunks.filter { it.filename == "Db.java" }
        assertEquals("Overlapping windows should merge into 1 chunk", 1, dbChunks.size)

        // Merged window: line 20-9=11 to max(20+9, 28+9)=37
        val chunkText = dbChunks[0].text
        assertTrue("Chunk should contain line around def 20", chunkText.contains("// line 20"))
        assertTrue("Chunk should contain line around def 28", chunkText.contains("// line 28"))
        assertTrue("Chunk should start at line 11", chunkText.contains("// line 11"))
        assertTrue("Chunk should end at line 37", chunkText.contains("// line 37"))
    }

    fun testMergeNonOverlappingWindows() {
        // Two definitions in the same file 40 lines apart → 2 separate chunks
        val lines = (0 until 80).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = listOf(
            DefinitionLocation("Wide.java", 10, docText, 80),
            DefinitionLocation("Wide.java", 50, docText, 80)  // 40 lines apart
        )

        val chunks = mergeAndExtractDefinitionChunks(locations)
        val wideChunks = chunks.filter { it.filename == "Wide.java" }
        assertEquals("Non-overlapping windows should stay separate", 2, wideChunks.size)
    }

    fun testMergeCapAtSixChunks() {
        // Create 8 locations in different files → should cap at 6
        val lines = (0 until 30).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = (0 until 8).map { i ->
            DefinitionLocation("File$i.java", 15, docText, 30)
        }

        val chunks = mergeAndExtractDefinitionChunks(locations)
        assertEquals("Should cap at $MAX_DEFINITION_CHUNKS chunks", MAX_DEFINITION_CHUNKS, chunks.size)
    }

    fun testMergeDeduplicatedByFileLine() {
        // Two identical locations should be handled (dedup is in collectDefinitionLocations,
        // but mergeAndExtract should handle duplicate input gracefully)
        val lines = (0 until 30).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = listOf(
            DefinitionLocation("Same.java", 15, docText, 30),
            DefinitionLocation("Same.java", 15, docText, 30)
        )

        val chunks = mergeAndExtractDefinitionChunks(locations)
        // Same file and same line → windows merge perfectly → 1 chunk
        assertEquals(1, chunks.size)
    }

    fun testMergeWindowClampedToFileStart() {
        // Definition at line 3 in a 10-line file → window starts at 0, not -6
        val lines = (0 until 10).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = listOf(DefinitionLocation("Start.java", 3, docText, 10))

        val chunks = mergeAndExtractDefinitionChunks(locations)
        assertEquals(1, chunks.size)
        val firstLine = chunks[0].text.lines().first()
        assertEquals("// line 0", firstLine)
    }

    fun testMergeWindowClampedToFileEnd() {
        // Definition at line 27 in a 30-line file → window ends at 29, not 36
        val lines = (0 until 30).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = listOf(DefinitionLocation("End.java", 27, docText, 30))

        val chunks = mergeAndExtractDefinitionChunks(locations)
        assertEquals(1, chunks.size)
        val lastLine = chunks[0].text.lines().last()
        assertEquals("// line 29", lastLine)
    }

    fun testMergeSkipsBlankText() {
        // Document with only blank lines around the definition → chunk should be skipped
        val lines = (0 until 20).map { "   " }
        val docText = lines.joinToString("\n")

        val locations = listOf(DefinitionLocation("Blank.java", 10, docText, 20))

        val chunks = mergeAndExtractDefinitionChunks(locations)
        assertTrue("Blank content should produce no chunks", chunks.isEmpty())
    }

    fun testMergeMultipleFilesProduceMultipleChunks() {
        val lines = (0 until 30).map { "// line $it" }
        val docText = lines.joinToString("\n")

        val locations = listOf(
            DefinitionLocation("A.java", 15, docText, 30),
            DefinitionLocation("B.java", 15, docText, 30)
        )

        val chunks = mergeAndExtractDefinitionChunks(locations)
        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.filename == "A.java" })
        assertTrue(chunks.any { it.filename == "B.java" })
    }

    // --- extractLinesFromText tests (pure function) ---

    fun testExtractLinesFromTextBasic() {
        val text = "line0\nline1\nline2\nline3\nline4"
        val result = extractLinesFromText(text, 1, 3)
        assertEquals("line1\nline2\nline3", result)
    }

    fun testExtractLinesFromTextClampedToStart() {
        val text = "line0\nline1\nline2"
        val result = extractLinesFromText(text, -5, 1)
        assertEquals("line0\nline1", result)
    }

    fun testExtractLinesFromTextClampedToEnd() {
        val text = "line0\nline1\nline2"
        val result = extractLinesFromText(text, 1, 100)
        assertEquals("line1\nline2", result)
    }

    fun testExtractLinesFromTextEmptyRange() {
        val text = "line0\nline1"
        val result = extractLinesFromText(text, 5, 3)
        assertEquals("", result)
    }

    fun testExtractLinesFromTextSingleLine() {
        val text = "line0\nline1\nline2"
        val result = extractLinesFromText(text, 1, 1)
        assertEquals("line1", result)
    }

    fun testExtractLinesFromTextEntireFile() {
        val text = "line0\nline1\nline2"
        val result = extractLinesFromText(text, 0, 2)
        assertEquals(text, result)
    }
}
