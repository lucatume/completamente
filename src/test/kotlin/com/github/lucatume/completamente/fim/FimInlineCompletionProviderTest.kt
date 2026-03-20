package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.TypingEvent

class FimInlineCompletionProviderTest : BaseCompletionTest() {

    fun testProviderIdIsCompletamenteFim() {
        val provider = FimInlineCompletionProvider()
        // InlineCompletionProviderID is a value class; compare via .id property.
        assertEquals("completamente.fim", provider.id.id)
    }

    fun testIsEnabledReturnsFalseWhenAutoSuggestionsOff() {
        val settings = SettingsState.getInstance()
        settings.autoSuggestions = false

        myFixture.configureByText("Test.kt", "fun main() {<caret>}")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset

        val typingEvent = TypingEvent.OneSymbol('x', offset)
        val event = InlineCompletionEvent.DocumentChange(typingEvent, editor)

        val provider = FimInlineCompletionProvider()
        assertFalse("isEnabled should return false when autoSuggestions is off", provider.isEnabled(event))

        // Restore default
        settings.autoSuggestions = true
    }

    fun testIsEnabledReturnsTrueForDocumentChangeWhenAutoSuggestionsOn() {
        // Ensure autoSuggestions is enabled.
        val settings = SettingsState.getInstance()
        settings.autoSuggestions = true

        // Set up an editor with cursor at end of line (nothing after cursor on the line).
        myFixture.configureByText("Test.kt", "fun main() {<caret>}")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset

        // Create a TypingEvent and DocumentChange event.
        val typingEvent = TypingEvent.OneSymbol('x', offset)
        val event = InlineCompletionEvent.DocumentChange(typingEvent, editor)

        val provider = FimInlineCompletionProvider()
        assertTrue("isEnabled should return true when autoSuggestions is on", provider.isEnabled(event))
    }
}
