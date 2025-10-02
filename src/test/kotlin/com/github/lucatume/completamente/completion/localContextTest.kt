package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.Settings

class localContextTest : BaseCompletionTest() {
    private var testCases: List<TestCase>? = null

    override fun setUp() {
        super.setUp()

        if(testCases != null){
            return
        }

        testCases = loadTestData()
    }

    fun testEmptyFileAtLineOneColumnZero() {
        val testCase = testCases!!.find { it.testId == "empty.ts::line_1_col_0" }
            ?: throw AssertionError("Test case empty.ts::line_1_col_0 not found")

        val request = makeInlineCompletionRequestAtVimPosition("empty.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineOneColumnTen() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_1_col_10" }
            ?: throw AssertionError("Test case large.ts::line_1_col_10 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineTenColumnZero() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_10_col_0" }
            ?: throw AssertionError("Test case large.ts::line_10_col_0 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineTenColumnFive() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_10_col_5" }
            ?: throw AssertionError("Test case large.ts::line_10_col_5 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineFiftyColumnTwenty() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_50_col_20" }
            ?: throw AssertionError("Test case large.ts::line_50_col_20 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineFiftyColumnZero() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_50_col_0" }
            ?: throw AssertionError("Test case large.ts::line_50_col_0 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineHundredColumnFifteen() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_100_col_15" }
            ?: throw AssertionError("Test case large.ts::line_100_col_15 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineThreeHundredColumnTen() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_300_col_10" }
            ?: throw AssertionError("Test case large.ts::line_300_col_10 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLineThreeFiftyColumnTwenty() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_350_col_20" }
            ?: throw AssertionError("Test case large.ts::line_350_col_20 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }

    fun testLargeFileLastLine() {
        val testCase = testCases!!.find { it.testId == "large.ts::line_454_col_0" }
            ?: throw AssertionError("Test case large.ts::line_454_col_0 not found")

        val request = makeInlineCompletionRequestAtVimPosition("large.ts", testCase.line, testCase.column)
        val settings = Settings(nPrefix = 256, nSuffix = 64)

        val context = buildLocalContext(request, settings, null)

        assertEquals("Prefix mismatch for ${testCase.testId}", testCase.expectedPrefix, context.prefix)
        assertEquals("Middle mismatch for ${testCase.testId}", testCase.expectedMiddle, context.middle)
        assertEquals("Suffix mismatch for ${testCase.testId}", testCase.expectedSuffix, context.suffix)
        assertEquals("Indent mismatch for ${testCase.testId}", testCase.expectedIndent, context.indent)
    }
}
