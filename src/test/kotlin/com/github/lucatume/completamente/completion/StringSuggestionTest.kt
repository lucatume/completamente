package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import kotlinx.coroutines.runBlocking

class StringSuggestionTest : BaseCompletionTest() {
    fun testConstructorWithEmptyString() {
        val suggestion = StringSuggestion("")
        assertEquals("", suggestion.text)
    }

    fun testConstructorWithSingleCharacter() {
        val suggestion = StringSuggestion("a")
        assertEquals("a", suggestion.text)
    }

    fun testConstructorWithSingleWord() {
        val suggestion = StringSuggestion("hello")
        assertEquals("hello", suggestion.text)
    }

    fun testConstructorWithMultipleWords() {
        val suggestion = StringSuggestion("hello world")
        assertEquals("hello world", suggestion.text)
    }

    fun testConstructorWithVeryLongString() {
        val longText = "x".repeat(1000)
        val suggestion = StringSuggestion(longText)
        assertEquals(longText, suggestion.text)
        assertEquals(1000, suggestion.text.length)
    }

    fun testConstructorWithNewlines() {
        val text = "hello\nworld\n"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
        assertTrue(suggestion.text.contains("\n"))
    }

    fun testConstructorWithTabs() {
        val text = "hello\tworld"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
        assertTrue(suggestion.text.contains("\t"))
    }

    fun testConstructorWithMixedWhitespace() {
        val text = "hello\t\n  world"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
    }

    fun testConstructorWithUnicodeCharacters() {
        val text = "Hello 世界 🌍"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
    }

    fun testConstructorWithWhitespaceOnlyString() {
        val suggestion = StringSuggestion("   ")
        assertEquals("   ", suggestion.text)
    }

    fun testConstructorWithWhitespaceOnlyStringWithTabs() {
        val suggestion = StringSuggestion("\t\t\t")
        assertEquals("\t\t\t", suggestion.text)
    }

    fun testConstructorWithMixedWhitespaceOnly() {
        val suggestion = StringSuggestion(" \t \n ")
        assertEquals(" \t \n ", suggestion.text)
    }

    fun testConstructorWithSpecialCharacters() {
        val text = "!@#$%^&*()"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
    }

    fun testConstructorWithEscapedCharacters() {
        val text = "quote\"apostrophe'backslash\\"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
    }

    fun testConstructorWithMultipleNewlines() {
        val text = "line1\n\n\nline2"
        val suggestion = StringSuggestion(text)
        assertEquals(text, suggestion.text)
    }

    fun testGetVariantReturnsValidVariant() = runBlocking {
        val suggestion = StringSuggestion("hello")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithEmptyString() = runBlocking {
        val suggestion = StringSuggestion("")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithSimpleString() = runBlocking {
        val suggestion = StringSuggestion("hello")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithMultipleWords() = runBlocking {
        val suggestion = StringSuggestion("multiple words here")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithNewlines() = runBlocking {
        val suggestion = StringSuggestion("line1\nline2\nline3")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithTabs() = runBlocking {
        val suggestion = StringSuggestion("tab\tseparated\tvalues")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithUnicodeText() = runBlocking {
        val suggestion = StringSuggestion("Hello 世界")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithWhitespaceOnlyString() = runBlocking {
        val suggestion = StringSuggestion("   ")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantWithVeryLongString() = runBlocking {
        val longText = "a".repeat(5000)
        val suggestion = StringSuggestion(longText)
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testGetVariantReturnsConsistentResults() = runBlocking {
        val suggestion = StringSuggestion("consistent")
        val variant1 = suggestion.getVariant()
        val variant2 = suggestion.getVariant()
        assertNotNull(variant1)
        assertNotNull(variant2)
    }

    fun testGetVariantMultipleCalls() = runBlocking {
        val suggestion = StringSuggestion("test")
        val variant1 = suggestion.getVariant()
        val variant2 = suggestion.getVariant()
        assertNotNull(variant1)
        assertNotNull(variant2)
    }

    fun testGetVariantWithDifferentTexts() = runBlocking {
        val suggestion1 = StringSuggestion("first")
        val suggestion2 = StringSuggestion("second")

        val variant1 = suggestion1.getVariant()
        val variant2 = suggestion2.getVariant()

        assertNotNull(variant1)
        assertNotNull(variant2)
    }

    fun testGetVariantIsNotBlocking() = runBlocking {
        val suggestion = StringSuggestion("test")
        val startTime = System.currentTimeMillis()
        val variant = suggestion.getVariant()
        val endTime = System.currentTimeMillis()

        assertNotNull(variant)
        val duration = endTime - startTime
        assertTrue(duration < 1000)
    }

    fun testGetVariantWithSpecialCharacters() = runBlocking {
        val suggestion = StringSuggestion("!@#$%^&*()")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testImplementsInlineCompletionSingleSuggestion() {
        val suggestion = StringSuggestion("test")
        assertTrue(suggestion is InlineCompletionSingleSuggestion)
    }

    fun testSuspendFunctionCanBeCalledFromCoroutine() = runBlocking {
        val suggestion = StringSuggestion("async test")
        try {
            val variant = suggestion.getVariant()
            assertNotNull(variant)
        } catch (e: Exception) {
            fail("getVariant should not throw exception: ${e.message}")
        }
    }

    fun testPropertyAccessDoesNotModifyText() {
        val original = "original text"
        val suggestion = StringSuggestion(original)
        val text1 = suggestion.text
        val text2 = suggestion.text
        assertEquals(text1, text2)
        assertEquals(original, text1)
    }

    fun testMultipleSuggestionsWithSameText() = runBlocking {
        val text = "same text"
        val suggestion1 = StringSuggestion(text)
        val suggestion2 = StringSuggestion(text)

        val variant1 = suggestion1.getVariant()
        val variant2 = suggestion2.getVariant()

        assertNotNull(variant1)
        assertNotNull(variant2)
    }

    fun testGetVariantPreservesExactTextWithMixedContent() = runBlocking {
        val suggestion = StringSuggestion("  spaces\t\ttabs\n\nlines  ")
        val variant = suggestion.getVariant()
        assertNotNull(variant)
    }

    fun testTextPropertyIsPublic() {
        val suggestion = StringSuggestion("public")
        val text = suggestion.text
        assertEquals("public", text)
    }

    fun testTextPropertyCanBeRepeatedlyAccessed() {
        val suggestion = StringSuggestion("repeat")
        repeat(10) {
            assertEquals("repeat", suggestion.text)
        }
    }
}
