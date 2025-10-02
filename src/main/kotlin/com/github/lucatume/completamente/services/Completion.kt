package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.completion.StringSuggestion
import com.github.lucatume.completamente.completion.SuggestionResult
import com.github.lucatume.completamente.completion.getSuggestionPure
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class Completion() : InlineCompletionProvider {
    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("completamente")

    // Track previous suggestion and last indent across coroutine calls
    @Volatile
    private var prev: List<String>? = null

    @Volatile
    private var indentLast: Int = -1

    @Volatile
    private var lastFile:String? = null

    @Volatile
    private var lastLine:Int? = null

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val project: Project = request.editor.project ?: return StringSuggestion("")

        val services = Services(
            settings = ApplicationManager.getApplication().getService(Settings::class.java),
            cache = project.service<SuggestionCache>(),
            chunksRingBuffer = project.service<ChunksRingBuffer>(),
            backgroundJobs = project.service<BackgroundJobs>(),
            httpClient = project.service<HttpClient>().getHttpClient()
        )

        val suggestionResult: SuggestionResult = getSuggestionPure(
            services,
            request,
            prev,
            indentLast,
            lastFile,
            lastLine
        )

        lastFile = request.editor.virtualFile.canonicalPath
        lastLine = request.editor.caretModel.logicalPosition.line

        prev = suggestionResult.prev
        indentLast = suggestionResult.indentLast

        return suggestionResult.suggestion
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return true
    }
}
