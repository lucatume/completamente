package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class StructureTest : BaseCompletionTest() {

    fun testExtractSurfaceReturnsEmptyForEmptyFile() {
        val psiFile = myFixture.configureByText("Empty.java", "")
        val project = myFixture.project
        val result = extractSurface(psiFile, project)
        assertTrue("Empty file should return empty string from fallback", result.isEmpty())
    }

    fun testExtractSurfaceFallbackCapsAt60Lines() {
        val lines = (0 until 80).map { "// line $it" }
        val content = lines.joinToString("\n")
        val psiFile = myFixture.configureByText("Big.txt", content)
        val result = extractSurfaceFallback(psiFile)
        val resultLines = result.lines()
        assertEquals("Fallback should return exactly 60 lines", 60, resultLines.size)
        assertEquals("// line 0", resultLines.first())
        assertEquals("// line 59", resultLines.last())
    }

    fun testExtractSurfaceNoExceptionOnPlainText() {
        val psiFile = myFixture.configureByText(
            "plain.txt",
            "This is just plain text with no structure at all."
        )
        val project = myFixture.project
        val result = extractSurface(psiFile, project)
        assertTrue("Plain text should return non-empty fallback", result.isNotEmpty())
    }
}
