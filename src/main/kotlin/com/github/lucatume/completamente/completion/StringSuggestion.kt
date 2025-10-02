package com.github.lucatume.completamente.completion

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant

// A class to represent a single string suggestion.
class StringSuggestion(val text: String) : InlineCompletionSingleSuggestion {
    override suspend fun getVariant() = InlineCompletionVariant.Companion.build {
        emit(InlineCompletionGrayTextElement(text))
    }
}
