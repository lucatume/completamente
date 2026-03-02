package com.github.lucatume.completamente.fim

import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion

/**
 * A no-op InlineCompletionProvider that suppresses the default IntelliJ
 * inline completions (e.g. Full Line Code Completion) so they don't
 * conflict with the plugin's own ghost-text suggestions.
 *
 * IntelliJ picks the first provider whose isEnabled() returns true and
 * does not fall through to others, so claiming the event and returning
 * an empty suggestion is enough to suppress the built-in provider.
 */
class NoOpInlineCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID =
        InlineCompletionProviderID("com.github.lucatume.completamente.NoOpInlineCompletionProvider")

    override fun isEnabled(event: InlineCompletionEvent): Boolean = true

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSingleSuggestion =
        InlineCompletionSingleSuggestion.build { }
}
