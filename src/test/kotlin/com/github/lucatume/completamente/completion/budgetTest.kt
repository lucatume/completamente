package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Chunk

class budgetTest : BaseCompletionTest() {

    // --- allocateBudget tests ---

    fun testBudgetMathDefaultContextSize() {
        val fc = FileContext(inputPrefix = "abc", inputSuffix = "def", prompt = "ghi", nIndent = 0)
        // fileTokens = estimateTokens("abc" + "ghi" + "def") = estimateTokens("abcghidef") = (9+2)/3 = 3
        // promptBudget = 32768 - 128 - 20 = 32620
        // remaining = 32620 - 3 = 32617
        // totalEstimatedTokens = 32620 - 32617 = 3
        val result = allocateBudget(fc, emptyList(), emptyList())
        assertEquals(3, result.totalEstimatedTokens)
        assertEquals(fc, result.fileContext)
        assertTrue(result.structureChunks.isEmpty())
        assertTrue(result.ringChunks.isEmpty())
    }

    fun testBudgetMathSmallContextSize() {
        val fc = FileContext(inputPrefix = "abc", inputSuffix = "def", prompt = "ghi", nIndent = 0)
        // fileTokens = 3
        // promptBudget = 1000 - 128 - 20 = 852
        // remaining = 852 - 3 = 849
        // totalEstimatedTokens = 852 - 849 = 3
        val result = allocateBudget(fc, emptyList(), emptyList(), contextSize = 1000)
        assertEquals(3, result.totalEstimatedTokens)
    }

    fun testStructureFilesAddedInOrderStoppedWhenBudgetExceeded() {
        // fileTokens for empty context = estimateTokens("") = (0+2)/3 = 0
        // promptBudget = 1000 - 128 - 20 = 852
        // remaining = 852
        val fc = FileContext(inputPrefix = "", inputSuffix = "", prompt = "", nIndent = 0)
        // chunk1: estimateTokens("<|file_sep|>file1.kt\naaaa") = estimateTokens of 25 chars = (25+2)/3 = 9
        val chunk1 = InfillExtraChunk(filename = "file1.kt", text = "aaaa")
        // chunk2: estimateTokens("<|file_sep|>file2.kt\nbbbb") = (25+2)/3 = 9
        val chunk2 = InfillExtraChunk(filename = "file2.kt", text = "bbbb")
        // chunk3: Make it huge so it won't fit. Need > 834 remaining tokens after chunk1+chunk2
        val bigText = "x".repeat(2550)
        val chunk3 = InfillExtraChunk(filename = "file3.kt", text = bigText)

        val result = allocateBudget(fc, listOf(chunk1, chunk2, chunk3), emptyList(), contextSize = 1000)
        assertEquals(2, result.structureChunks.size)
        assertEquals("file1.kt", result.structureChunks[0].filename)
        assertEquals("file2.kt", result.structureChunks[1].filename)
    }

    fun testLargeStructureFileSkippedSmallerNextOneAdded() {
        val fc = FileContext(inputPrefix = "", inputSuffix = "", prompt = "", nIndent = 0)
        // promptBudget = 100 - 10 - 5 = 85, fileTokens = 0, remaining = 85
        // bigChunk: estimateTokens("<|file_sep|>big.kt\n" + "x"*300) = estimateTokens of 319 chars = (319+2)/3 = 107 > 85, skip
        val bigChunk = InfillExtraChunk(filename = "big.kt", text = "x".repeat(300))
        // smallChunk: estimateTokens("<|file_sep|>sm.kt\nabc") = (21+2)/3 = 7, fits
        val smallChunk = InfillExtraChunk(filename = "sm.kt", text = "abc")

        val result = allocateBudget(fc, listOf(bigChunk, smallChunk), emptyList(), contextSize = 100, nPredict = 10, overheadTokens = 5)
        assertEquals(1, result.structureChunks.size)
        assertEquals("sm.kt", result.structureChunks[0].filename)
    }

    fun testRingChunksFillRemainingSpaceAfterStructureFiles() {
        val fc = FileContext(inputPrefix = "", inputSuffix = "", prompt = "", nIndent = 0)
        // promptBudget = 1000 - 128 - 20 = 852, fileTokens = 0
        val struct1 = InfillExtraChunk(filename = "s.kt", text = "aaa")
        // struct1 tokens: estimateTokens("<|file_sep|>s.kt\naaa") = (20+2)/3 = 7
        val ring1 = InfillExtraChunk(filename = "r.kt", text = "bbb")
        // ring1 tokens: estimateTokens("<|file_sep|>r.kt\nbbb") = (20+2)/3 = 7

        val result = allocateBudget(fc, listOf(struct1), listOf(ring1), contextSize = 1000)
        assertEquals(1, result.structureChunks.size)
        assertEquals(1, result.ringChunks.size)
        // total = 852 - (852 - 7 - 7) = 14
        assertEquals(14, result.totalEstimatedTokens)
    }

    fun testFileContextConsumesEntireBudgetEmptyStructureAndRing() {
        // Make file context consume all budget
        // promptBudget = 100 - 10 - 5 = 85
        // Need fileTokens >= 85, so text length >= 85*3-2 = 253
        val bigPrefix = "y".repeat(260)
        val fc = FileContext(inputPrefix = bigPrefix, inputSuffix = "", prompt = "", nIndent = 0)
        val struct1 = InfillExtraChunk(filename = "s.kt", text = "aaa")
        val ring1 = InfillExtraChunk(filename = "r.kt", text = "bbb")

        val result = allocateBudget(fc, listOf(struct1), listOf(ring1), contextSize = 100, nPredict = 10, overheadTokens = 5)
        assertTrue(result.structureChunks.isEmpty())
        assertTrue(result.ringChunks.isEmpty())
        // fileTokens = estimateTokens("y"*260) = (260+2)/3 = 87, which exceeds promptBudget (85)
        assertEquals(87, result.totalEstimatedTokens)
    }

    fun testEmptyInputsNoStructureNoRing() {
        val fc = FileContext(inputPrefix = "abc", inputSuffix = "def", prompt = "ghi", nIndent = 0)
        val result = allocateBudget(fc, emptyList(), emptyList())
        assertTrue(result.structureChunks.isEmpty())
        assertTrue(result.ringChunks.isEmpty())
        assertEquals(3, result.totalEstimatedTokens)
    }

    fun testTotalEstimatedTokensIsCorrect() {
        val fc = FileContext(inputPrefix = "", inputSuffix = "", prompt = "", nIndent = 0)
        // promptBudget = 500 - 50 - 10 = 440, fileTokens = 0
        // chunk: estimateTokens("<|file_sep|>a.kt\nhi") = (19+2)/3 = 7
        val chunk = InfillExtraChunk(filename = "a.kt", text = "hi")
        // remaining = 440 - 7 = 433
        // total = 440 - 433 = 7
        val result = allocateBudget(fc, listOf(chunk), emptyList(), contextSize = 500, nPredict = 50, overheadTokens = 10)
        assertEquals(7, result.totalEstimatedTokens)
    }

    // --- filterRingChunks tests ---

    fun testFilterRingChunksRemovesAboveThreshold() {
        // Two identical chunks should have similarity 1.0 > 0.5, so filtered out
        val chunks = listOf(
            Chunk(text = "line1\nline2\nline3", time = 0, filename = "a.kt")
        )
        val cursorContext = "line1\nline2\nline3"
        val result = filterRingChunks(chunks, cursorContext)
        assertTrue(result.isEmpty())
    }

    fun testFilterRingChunksKeepsAtOrBelowThreshold() {
        // No common lines => similarity 0.0 <= 0.5
        val chunks = listOf(
            Chunk(text = "alpha\nbeta\ngamma", time = 0, filename = "a.kt")
        )
        val cursorContext = "delta\nepsilon\nzeta"
        val result = filterRingChunks(chunks, cursorContext)
        assertEquals(1, result.size)
        assertEquals("a.kt", result[0].filename)
        assertEquals("alpha\nbeta\ngamma", result[0].text)
    }

    fun testFilterRingChunksEmptyInput() {
        val result = filterRingChunks(emptyList(), "some context")
        assertTrue(result.isEmpty())
    }

    fun testFilterRingChunksEmptyCursorContext() {
        // chunkSim(chunk.lines, emptyList) => c1 is empty, c0 non-empty => 0.0 <= 0.5, keep
        val chunks = listOf(
            Chunk(text = "line1\nline2\nline3", time = 0, filename = "a.kt")
        )
        val result = filterRingChunks(chunks, "")
        assertEquals(1, result.size)
    }

    fun testFilterRingChunksResultSortedByFilename() {
        val chunks = listOf(
            Chunk(text = "unique1\nunique2\nunique3", time = 0, filename = "z.kt"),
            Chunk(text = "other1\nother2\nother3", time = 0, filename = "a.kt"),
            Chunk(text = "more1\nmore2\nmore3", time = 0, filename = "m.kt")
        )
        val cursorContext = "nomatch1\nnomatch2\nnomatch3"
        val result = filterRingChunks(chunks, cursorContext)
        assertEquals(3, result.size)
        assertEquals("a.kt", result[0].filename)
        assertEquals("m.kt", result[1].filename)
        assertEquals("z.kt", result[2].filename)
    }

    fun testFilterRingChunksExactThresholdKept() {
        // chunkSim with exactly 0.5: 1 common line out of 1+1 = 2*1/2 = 1.0? No.
        // Need: 2*common/(lines0+lines1) = 0.5 => common = 0.5*(lines0+lines1)/2
        // With c0=["a","b","c","d"], c1=["a","x","y","z"]: common=1, sim=2*1/(4+4)=0.25, kept
        // With c0=["a","b"], c1=["a","c"]: common=1, sim=2*1/(2+2)=0.5, kept (at threshold)
        val chunks = listOf(
            Chunk(text = "a\nc", time = 0, filename = "half.kt")
        )
        val cursorContext = "a\nb"
        val result = filterRingChunks(chunks, cursorContext)
        assertEquals(1, result.size)
    }

    fun testFilterRingChunksAboveThresholdExcluded() {
        // c0=["a","b","c"], c1=["a","b","d"]: common=2, sim=2*2/(3+3)=0.6667 > 0.5, excluded
        val chunks = listOf(
            Chunk(text = "a\nb\nc", time = 0, filename = "similar.kt")
        )
        val cursorContext = "a\nb\nd"
        val result = filterRingChunks(chunks, cursorContext)
        assertTrue(result.isEmpty())
    }
}
