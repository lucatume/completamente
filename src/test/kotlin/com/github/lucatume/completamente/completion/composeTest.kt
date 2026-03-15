package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings

class composeTest : BaseCompletionTest() {

    private fun testChunk(filename: String, text: String) = Chunk(
        text = text, time = System.currentTimeMillis(), filename = filename,
        estimatedTokens = estimateTokens(text)
    )

    private fun defaultSettings() = Settings()

    // --- Small file, no extras ---

    fun testSmallFileNoExtras() {
        val content = "line0\nline1\nline2"
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 1,
            cursorColumn = 3,
            structureFiles = emptyList(),
            ringChunks = emptyList(),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        // buildContext at line 1, col 3 → prefix="line0\n", prompt="lin", suffix="e1\nline2"
        assertEquals("line0\n", req.inputPrefix)
        assertEquals("lin", req.prompt)
        assertEquals("e1\nline2", req.inputSuffix)
        assertTrue(req.inputExtra.isEmpty())
    }

    // --- Small file, with structure files ---

    fun testSmallFileWithStructureFiles() {
        val content = "hello"
        val sf1 = InfillExtraChunk(filename = "b.kt", text = "struct b")
        val sf2 = InfillExtraChunk(filename = "a.kt", text = "struct a")
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = listOf(sf1, sf2),
            ringChunks = emptyList(),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        // Structure files should appear in inputExtra (sorted by filename from allocateBudget input order)
        assertTrue(req.inputExtra.isNotEmpty())
        // They are passed as-is to allocateBudget which preserves order
        assertTrue(req.inputExtra.any { it.filename == "a.kt" })
        assertTrue(req.inputExtra.any { it.filename == "b.kt" })
    }

    // --- Small file, with ring chunks ---

    fun testSmallFileWithRingChunks() {
        val content = "hello"
        val rc = testChunk("ring.kt", "some ring text that is different\n")
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = emptyList(),
            ringChunks = listOf(rc),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        assertTrue(req.inputExtra.isNotEmpty())
        assertEquals("ring.kt", req.inputExtra[0].filename)
    }

    // --- Small file, with both structure files and ring chunks ---

    fun testSmallFileWithBothStructureAndRing() {
        val content = "hello"
        val sf = InfillExtraChunk(filename = "struct.kt", text = "structure text")
        val rc = testChunk("ring.kt", "ring text that differs from cursor context\n")
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = listOf(sf),
            ringChunks = listOf(rc),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        // Structure chunks come before ring chunks in inputExtra
        assertTrue(req.inputExtra.size >= 2)
        assertEquals("struct.kt", req.inputExtra[0].filename)
        assertEquals("ring.kt", req.inputExtra[1].filename)
    }

    // --- Ring chunk similarity eviction ---

    fun testRingChunkSimilarityEviction() {
        // Ring chunk text identical to cursor context should be excluded
        val content = "hello"
        val rc = testChunk("ring.kt", "hello")
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = emptyList(),
            ringChunks = listOf(rc),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        // The ring chunk is very similar to cursor context, should be filtered out
        assertTrue(req.inputExtra.isEmpty())
    }

    // --- Large file triggers windowed context ---

    fun testLargeFileTriggersWindowedContext() {
        // Use a small contextSize so maxFileTokens = contextSize / 3 = 100,
        // making the file exceed the token budget and trigger windowing.
        // The windowed mode uses prefixLines=512 by default, so place the cursor
        // more than 512 lines from the start so the first line gets dropped.
        val firstLine = "FIRST_LINE_MARKER"
        val otherLine = "x".repeat(50)
        val lines = mutableListOf(firstLine)
        for (i in 1..700) lines.add(otherLine)
        val content = lines.joinToString("\n")
        val cursorLine = 650
        val ctx = CompletionContext(
            filePath = "big.kt",
            fileContent = content,
            cursorLine = cursorLine,
            cursorColumn = 0,
            structureFiles = emptyList(),
            ringChunks = emptyList(),
            settings = Settings(contextSize = 300)
        )
        val req = composeInfillRequest(ctx)
        // Windowed context should have dropped the very first line
        assertFalse(req.inputPrefix.contains("FIRST_LINE_MARKER"))
        assertNotNull(req.inputSuffix)
    }

    // --- Whitespace-only line ---

    fun testWhitespaceOnlyLine() {
        val content = "line0\n   \nline2"
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 1,
            cursorColumn = 2,
            structureFiles = emptyList(),
            ringChunks = emptyList(),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        assertEquals("", req.prompt)
        assertEquals(0, req.nIndent)
        // The suffix should contain the rest of the whitespace line and subsequent content
        assertTrue(req.inputSuffix.contains(" \nline2"))
    }

    // --- Settings nPredict respected ---

    fun testSettingsNPredictRespected() {
        val content = "hello"
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = emptyList(),
            ringChunks = emptyList(),
            settings = Settings(nPredict = 256)
        )
        val req = composeInfillRequest(ctx)
        assertEquals(256, req.nPredict)
    }

    // --- Settings contextSize respected ---

    fun testSettingsContextSizeLimitsBudget() {
        val content = "hello"
        // Create a large structure file
        val bigText = "a".repeat(900) // ~300 tokens
        val sf = InfillExtraChunk(filename = "big.kt", text = bigText)
        // contextSize=200 → promptBudget = 200-128-20 = 52, file uses ~2 tokens, only ~50 left
        // The structure chunk needs ~300 tokens, won't fit
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = listOf(sf),
            ringChunks = emptyList(),
            settings = Settings(contextSize = 200)
        )
        val req = composeInfillRequest(ctx)
        // The big structure file should not fit in the small budget
        assertTrue(req.inputExtra.isEmpty())
    }

    // --- Empty file ---

    fun testEmptyFile() {
        val ctx = CompletionContext(
            filePath = "empty.kt",
            fileContent = "",
            cursorLine = 0,
            cursorColumn = 0,
            structureFiles = emptyList(),
            ringChunks = emptyList(),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        assertEquals("", req.inputPrefix)
        assertEquals("", req.prompt)
        assertEquals(0, req.nIndent)
        assertTrue(req.inputExtra.isEmpty())
    }

    // --- Ring chunk with same filename as current file is excluded ---

    fun testRingChunkWithSameFilenameAsCurrentFileIsExcluded() {
        val content = "hello"
        val sameFileChunk = testChunk("Test.kt", "some stale content from the same file\n")
        val otherFileChunk = testChunk("Other.kt", "content from a different file entirely\n")
        val ctx = CompletionContext(
            filePath = "Test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 5,
            structureFiles = emptyList(),
            ringChunks = listOf(sameFileChunk, otherFileChunk),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        // The ring chunk matching the current file path should be excluded
        assertFalse(req.inputExtra.any { it.filename == "Test.kt" })
        // The other file's ring chunk should still be present
        assertTrue(req.inputExtra.any { it.filename == "Other.kt" })
    }

    // --- inputExtra = structureChunks + ringChunks ---

    fun testInputExtraIsStructurePlusRing() {
        val content = "code"
        val sf1 = InfillExtraChunk(filename = "s1.kt", text = "struct one")
        val sf2 = InfillExtraChunk(filename = "s2.kt", text = "struct two")
        val rc1 = testChunk("r1.kt", "ring one is different text\n")
        val rc2 = testChunk("r2.kt", "ring two is also different text\n")
        val ctx = CompletionContext(
            filePath = "test.kt",
            fileContent = content,
            cursorLine = 0,
            cursorColumn = 4,
            structureFiles = listOf(sf1, sf2),
            ringChunks = listOf(rc1, rc2),
            settings = defaultSettings()
        )
        val req = composeInfillRequest(ctx)
        // Structure chunks first, then ring chunks (each group sorted by filename from filterRingChunks)
        assertEquals(4, req.inputExtra.size)
        assertEquals("s1.kt", req.inputExtra[0].filename)
        assertEquals("s2.kt", req.inputExtra[1].filename)
        // Ring chunks are sorted by filename in filterRingChunks
        assertEquals("r1.kt", req.inputExtra[2].filename)
        assertEquals("r2.kt", req.inputExtra[3].filename)
    }
}
