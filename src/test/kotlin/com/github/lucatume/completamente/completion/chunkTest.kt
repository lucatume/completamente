package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings

class chunkTest : BaseCompletionTest() {
    fun testChunkSimBothEmpty() {
        val result = chunkSim(emptyList(), emptyList())
        assertEquals(1.0, result)
    }

    fun testChunkSimFirstEmptySecondNonEmpty() {
        val result = chunkSim(emptyList(), listOf("line1", "line2"))
        assertEquals(0.0, result)
    }

    fun testChunkSimFirstNonEmptySecondEmpty() {
        val result = chunkSim(listOf("line1", "line2"), emptyList())
        assertEquals(0.0, result)
    }

    fun testChunkSimIdenticalSingleLine() {
        val result = chunkSim(listOf("same"), listOf("same"))
        assertEquals(1.0, result)
    }

    fun testChunkSimIdenticalMultipleLines() {
        val c0 = listOf("line1", "line2", "line3")
        val c1 = listOf("line1", "line2", "line3")
        val result = chunkSim(c0, c1)
        assertEquals(1.0, result)
    }

    fun testChunkSimNoCommonLines() {
        val c0 = listOf("a", "b")
        val c1 = listOf("c", "d")
        val result = chunkSim(c0, c1)
        assertEquals(0.0, result)
    }

    fun testChunkSimPartialMatchFiftyPercent() {
        val c0 = listOf("line1", "line2", "line3", "line4")
        val c1 = listOf("line1", "line2", "x", "y")
        val result = chunkSim(c0, c1)
        val expected = 2.0 * 2 / (4 + 4)
        assertEquals(expected, result)
    }

    fun testChunkSimFormulaVerification() {
        val c0 = listOf("a", "b", "c")
        val c1 = listOf("a", "d", "e")
        val result = chunkSim(c0, c1)
        val expected = 2.0 * 1 / (3 + 3)
        assertEquals(expected, result)
    }

    fun testChunkSimSingleLineVsMultipleLines() {
        val c0 = listOf("line1")
        val c1 = listOf("line1", "line2", "line3")
        val result = chunkSim(c0, c1)
        val expected = 2.0 * 1 / (1 + 3)
        assertEquals(expected, result)
    }

    fun testChunkSimCaseSensitivity() {
        val c0 = listOf("Line")
        val c1 = listOf("line")
        val result = chunkSim(c0, c1)
        assertEquals(0.0, result)
    }

    fun testChunkSimLargeChunks() {
        val lines = (1..100).map { "line$it" }
        val c0 = lines
        val c1 = lines
        val result = chunkSim(c0, c1)
        assertEquals(1.0, result)
    }

    fun testChunkSimPartialLargeChunks() {
        val lines = (1..100).map { "line$it" }
        val c0 = lines
        val c1 = (1..50).map { "line$it" } + (1..50).map { "different$it" }
        val result = chunkSim(c0, c1)
        val expected = 2.0 * 50 / (100 + 100)
        assertEquals(expected, result)
    }

    fun testPickChunkDisabled() {
        val settings = Settings(ringNChunks = 0)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(0, ringChunks.size)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkTooSmall() {
        val settings = Settings(ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFitsCompletely() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3", "line4", "line5")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
        assertEquals("line1\nline2\nline3\nline4\nline5\n", ringQueued[0].text)
        assertEquals("test.txt", ringQueued[0].filename)
    }

    fun testPickChunkExactMatchInRingChunks() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val chunkText = "line1\nline2\nline3\n"
        val ringChunks = mutableListOf(Chunk(text = chunkText, time = 100L, filename = "existing.txt"))
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkExactMatchInRingQueued() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val chunkText = "line1\nline2\nline3\n"
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(Chunk(text = chunkText, time = 100L, filename = "existing.txt"))
        val text = listOf("line1", "line2", "line3")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkSimilarWithEvictTrue() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val existingText = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n"
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(Chunk(text = existingText, time = 100L, filename = "existing.txt"))
        val text = listOf("line1", "line2", "line3", "line4", "line5", "line6", "line7", "line8", "line9")

        val result = pickChunk(text, true, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(1, result)
        assertEquals(1, ringQueued.size)
        assertEquals("test.txt", ringQueued[0].filename)
    }

    fun testPickChunkSimilarWithEvictFalse() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val existingText = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n"
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(Chunk(text = existingText, time = 100L, filename = "existing.txt"))
        val text = listOf("line1", "line2", "line3", "line4", "line5", "line6", "line7", "line8", "line9")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
        assertEquals("existing.txt", ringQueued[0].filename)
    }

    fun testPickChunkNewChunkAddsToQueued() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3", "unique")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
        assertEquals("test.txt", ringQueued[0].filename)
    }

    fun testPickChunkQueuedAtMaxCapacityRemovesOldest() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16, maxQueuedChunks = 2)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(
            Chunk(text = "first\n", time = 100L, filename = "first.txt"),
            Chunk(text = "second\n", time = 200L, filename = "second.txt")
        )
        val text = listOf("line1", "line2", "line3")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(2, ringQueued.size)
        assertEquals("second.txt", ringQueued[0].filename)
        assertEquals("test.txt", ringQueued[1].filename)
    }

    fun testPickChunkMultipleSimilarEvictsAll() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16, maxQueuedChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(
            Chunk(text = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n", time = 100L, filename = "first.txt"),
            Chunk(text = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nlineX\n", time = 200L, filename = "second.txt")
        )
        val text = listOf("line1", "line2", "line3", "line4", "line5", "line6", "line7", "line8", "line9")

        val result = pickChunk(text, true, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(2, result)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkEmptyQueuesAndRings() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3")

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkPreservesNewlines() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3")

        pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals("line1\nline2\nline3\n", ringQueued[0].text)
    }

    fun testPickChunkSetsFilename() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3")

        pickChunk(text, false, settings, ringChunks, ringQueued, "myfile.kt")

        assertEquals("myfile.kt", ringQueued[0].filename)
    }

    fun testPickChunkSetsTimeToCurrentMs() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3")
        val beforeTime = System.currentTimeMillis()

        pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        val afterTime = System.currentTimeMillis()
        val chunkTime = ringQueued[0].time
        assertTrue(chunkTime >= beforeTime && chunkTime <= afterTime)
    }

    fun testPickChunkLargerThanChunkSize() {
        val settings = Settings(ringChunkSize = 10, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()
        val text = (1..20).map { "line$it" }

        val result = pickChunk(text, false, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
        val chunkLines = ringQueued[0].text.trimEnd('\n').split("\n")
        assertTrue(chunkLines.size <= 5)
    }

    fun testPickChunkFromFileModifiedSkips() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, 1, true, settings, ringChunks, ringQueued)

        assertEquals(-1, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFromFileEmptyFile() {
        myFixture.configureByText("empty.kt", "line1")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, null, false, settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFromFileCursorAtSpecificLine() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\nline6\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        pickChunkFromFile(file, 3, false, settings, ringChunks, ringQueued)

        assertEquals(1, ringQueued.size)
        assertTrue(ringQueued[0].text.contains("line2") || ringQueued[0].text.contains("line3"))
    }

    fun testPickChunkFromFileCursorNull() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\nline6\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, null, false, settings, ringChunks, ringQueued)

        assertTrue(result >= 0 || result == 0)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkFromFileCursorAfterEnd() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, 10, false, settings, ringChunks, ringQueued)

        assertTrue(result >= 0 || result == 0)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkFromFileCallsPickChunkWithDoEvictTrue() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val existingQueued = mutableListOf(
            Chunk(text = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n", time = 100L, filename = "existing.txt")
        )

        pickChunkFromFile(file, 5, false, settings, ringChunks, existingQueued)

        val ringQueued = existingQueued
        assertTrue(ringQueued.size <= 1)
    }

    fun testPickChunkFromFileSingleLineFile() {
        myFixture.configureByText("test.kt", "line1\nline2")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, 1, false, settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFromFileVeryLargeFile() {
        val content = (1..1000).joinToString("\n") { "line$it" }
        myFixture.configureByText("test.kt", content)
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, 500, false, settings, ringChunks, ringQueued)

        assertTrue(result >= 0 || result == 0)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkFromFileCursorAtStart() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, 1, false, settings, ringChunks, ringQueued)

        assertTrue(result >= 0 || result == 0)
    }

    fun testPickChunkFromFileCursorAtEnd() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromFile(file, 5, false, settings, ringChunks, ringQueued)

        assertTrue(result >= 0 || result == 0)
    }

    fun testPickChunkFromFileUsesRealFile() {
        val fileContent = "val x = 1\nval y = 2\nval z = 3\nval a = 4\nval b = 5\n"
        myFixture.configureByText("variables.kt", fileContent)
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        pickChunkFromFile(file, 2, false, settings, ringChunks, ringQueued)

        assertEquals(1, ringQueued.size)
        assertTrue(ringQueued[0].text.contains("val"))
    }

    fun testPickChunkFromFileSetsFilenameToFilePath() {
        myFixture.configureByText("test.kt", "line1\nline2\nline3\nline4\nline5\n")
        val file = myFixture.file.virtualFile
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        pickChunkFromFile(file, 2, false, settings, ringChunks, ringQueued)

        assertEquals(file.path, ringQueued[0].filename)
    }

    fun testPickChunkFromTextSingleLine() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromText("single line", "test", settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFromTextMultiLine() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromText("line1\nline2\nline3\nline4", "test", settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkFromTextEmptyString() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromText("", "test", settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFromTextTrailingNewline() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        pickChunkFromText("line1\nline2\nline3\n", "test", settings, ringChunks, ringQueued)

        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkFromTextCallsPickChunkWithDoEvictTrue() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val existingQueued = mutableListOf(
            Chunk(text = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n", time = 100L, filename = "existing")
        )

        pickChunkFromText("line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9", "test", settings, ringChunks, existingQueued)

        assertTrue(existingQueued.size <= 1)
    }

    fun testPickChunkFromTextUsesProvidedName() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        pickChunkFromText("line1\nline2\nline3\nline4", "clipboard", settings, ringChunks, ringQueued)

        assertEquals("clipboard", ringQueued[0].filename)
    }

    fun testPickChunkFromTextTwoLines() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromText("line1\nline2", "test", settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(0, ringQueued.size)
    }

    fun testPickChunkFromTextMixedLineEndings() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        pickChunkFromText("line1\rline2\nline3\r\nline4", "test", settings, ringChunks, ringQueued)

        assertEquals(1, ringQueued.size)
    }

    fun testPickChunkFromTextVeryLongText() {
        val longText = (1..1000).joinToString("\n") { "line$it" }
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf<Chunk>()

        val result = pickChunkFromText(longText, "test", settings, ringChunks, ringQueued)

        assertEquals(0, result)
        assertEquals(1, ringQueued.size)
    }

    fun testChunkSimDuplicateLinesInOneChunk() {
        val c0 = listOf("line", "line", "line")
        val c1 = listOf("line", "x", "y")
        val result = chunkSim(c0, c1)
        val expected = 2.0 * 3 / (3 + 3)
        assertEquals(expected, result)
    }

    fun testChunkSimAllSame() {
        val c0 = listOf("same", "same", "same")
        val c1 = listOf("same", "same", "same")
        val result = chunkSim(c0, c1)
        assertEquals(1.0, result)
    }

    fun testPickChunkQueuedMultipleRemovals() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16, maxQueuedChunks = 3)
        val ringChunks = mutableListOf<Chunk>()
        val ringQueued = mutableListOf(
            Chunk(text = "first\n", time = 100L, filename = "1"),
            Chunk(text = "second\n", time = 200L, filename = "2"),
            Chunk(text = "third\n", time = 300L, filename = "3")
        )
        val text = listOf("line1", "line2", "line3")

        pickChunk(text, false, settings, ringChunks, ringQueued, "new")

        assertEquals(3, ringQueued.size)
        assertEquals("2", ringQueued[0].filename)
        assertEquals("3", ringQueued[1].filename)
        assertEquals("new", ringQueued[2].filename)
    }

    fun testPickChunkSimilarFromRingChunks() {
        val settings = Settings(ringChunkSize = 64, ringNChunks = 16)
        val ringChunks = mutableListOf(
            Chunk(text = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n", time = 100L, filename = "existing.txt")
        )
        val ringQueued = mutableListOf<Chunk>()
        val text = listOf("line1", "line2", "line3", "line4", "line5", "line6", "line7", "line8", "line9")

        val result = pickChunk(text, true, settings, ringChunks, ringQueued, "test.txt")

        assertEquals(1, result)
        assertEquals(0, ringChunks.size)
        assertEquals(1, ringQueued.size)
    }

    fun testChunkSimOneLineEachWithMatch() {
        val result = chunkSim(listOf("hello"), listOf("hello"))
        assertEquals(1.0, result)
    }

    fun testChunkSimOneLineEachNoMatch() {
        val result = chunkSim(listOf("hello"), listOf("world"))
        assertEquals(0.0, result)
    }
}
