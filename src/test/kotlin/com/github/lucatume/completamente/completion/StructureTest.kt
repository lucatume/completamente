package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class StructureTest : BaseCompletionTest() {

    fun testExtractSurfaceReturnsEmptyForEmptyFile() {
        val psiFile = myFixture.configureByText("Empty.java", "")
        val project = myFixture.project
        val result = extractSurface(psiFile, project)
        assertEquals("", result)
    }

    fun testExtractSurfaceFallbackCapsAt60Lines() {
        val lines = (0 until 80).map { "// line $it" }
        val content = lines.joinToString("\n")
        val psiFile = myFixture.configureByText("Big.txt", content)
        val result = extractSurfaceFallback(psiFile)
        val resultLines = result.lines()
        assertEquals(60, resultLines.size)
        assertEquals("// line 0", resultLines.first())
        assertEquals("// line 59", resultLines.last())
    }

    fun testExtractSurfaceOnPlainTextReturnsContent() {
        val psiFile = myFixture.configureByText(
            "plain.txt",
            "This is just plain text with no structure at all."
        )
        val project = myFixture.project
        val result = extractSurface(psiFile, project)
        assertEquals("This is just plain text with no structure at all.", result)
    }

    fun testExtractSurfaceFallbackExactly60Lines() {
        val lines = (0 until 60).map { "// line $it" }
        val content = lines.joinToString("\n")
        val psiFile = myFixture.configureByText("Exact60.txt", content)
        val result = extractSurfaceFallback(psiFile)
        val resultLines = result.lines()
        assertEquals(60, resultLines.size)
        assertEquals("// line 0", resultLines.first())
        assertEquals("// line 59", resultLines.last())
    }

    fun testExtractSurfaceFallbackUnder60Lines() {
        val lines = (0 until 10).map { "// line $it" }
        val content = lines.joinToString("\n")
        val psiFile = myFixture.configureByText("Small.txt", content)
        val result = extractSurfaceFallback(psiFile)
        val resultLines = result.lines()
        assertEquals(10, resultLines.size)
        assertEquals("// line 0", resultLines.first())
        assertEquals("// line 9", resultLines.last())
    }

    fun testExtractSurfaceFallbackSingleLine() {
        val psiFile = myFixture.configureByText("OneLine.txt", "single line content")
        val result = extractSurfaceFallback(psiFile)
        assertEquals("single line content", result)
    }

    fun testExtractSurfaceFallbackWhitespaceOnlyFile() {
        val psiFile = myFixture.configureByText("Whitespace.txt", "   \n  \n   ")
        val result = extractSurfaceFallback(psiFile)
        val resultLines = result.lines()
        assertEquals(3, resultLines.size)
    }
}
