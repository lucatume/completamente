package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.completion.CompletionContext
import com.github.lucatume.completamente.completion.InfillClient
import com.github.lucatume.completamente.completion.buildStructureChunks
import com.github.lucatume.completamente.completion.composeInfillRequest
import com.github.lucatume.completamente.completion.estimateTokens
import com.github.lucatume.completamente.completion.shouldDiscardSuggestion
import com.github.lucatume.completamente.completion.shouldSuppressAutoTrigger
import com.github.lucatume.completamente.services.CacheWarmingService
import com.github.lucatume.completamente.services.ChunksRingBuffer
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Provides FIM inline completions via the llama.cpp `/infill` endpoint.
 *
 * Registered declaratively in `plugin.xml` as an `inline.completion.provider`.
 * The platform invokes [getSuggestion] on each keystroke and handles cancel-and-replace
 * automatically via coroutine cancellation.
 */
class FimInlineCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("completamente.fim")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = SettingsState.getInstance().toSettings()
        if (!settings.autoSuggestions) return false

        // Check auto-trigger gating: suppress when too many chars after cursor.
        val editor = when (event) {
            is InlineCompletionEvent.DocumentChange -> event.editor
            else -> null
        }
        if (editor != null) {
            val offset = editor.caretModel.offset
            val document = editor.document
            val lineNumber = document.getLineNumber(offset)
            val lineEnd = document.getLineEndOffset(lineNumber)
            val lineAfterCursor = document.getText(com.intellij.openapi.util.TextRange(offset, lineEnd))
            if (shouldSuppressAutoTrigger(lineAfterCursor)) return false
        }

        return true
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val document = request.document
        val psiFile = request.file
        val project = psiFile.project
        val settings = SettingsState.getInstance().toSettings()

        val offset = editor.caretModel.offset
        val cursorLine = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(cursorLine)
        val cursorColumn = offset - lineStart
        val fileContent = document.text
        val filePath = psiFile.virtualFile?.path ?: psiFile.name

        // Build structure chunks (requires read action for PSI access).
        // Use windowed mode for large files, matching compose.kt's maxFileTokens logic.
        val maxFileTokens = settings.contextSize / 3
        val wholeFile = estimateTokens(fileContent) <= maxFileTokens
        val structureChunks = readAction {
            buildStructureChunks(psiFile, wholeFile)
        }

        coroutineContext.ensureActive()

        // Get ring chunks from the project service.
        val chunksRingBuffer = project.service<ChunksRingBuffer>()
        val ringChunks = chunksRingBuffer.getRingChunks().toList()

        // Compose the infill request.
        val ctx = CompletionContext(
            filePath = filePath,
            fileContent = fileContent,
            cursorLine = cursorLine,
            cursorColumn = cursorColumn,
            structureFiles = structureChunks,
            ringChunks = ringChunks,
            settings = settings
        )
        val infillRequest = composeInfillRequest(ctx)

        coroutineContext.ensureActive()

        // Send the request on IO dispatcher (respects coroutine cancellation via thread interrupt).
        val response = try {
            withContext(Dispatchers.IO) {
                val client = InfillClient(settings.serverUrl)
                client.sendCompletion(infillRequest)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return InlineCompletionSingleSuggestion.build {}
        }

        coroutineContext.ensureActive()

        val suggestion = response.content
        if (suggestion.isEmpty()) {
            return InlineCompletionSingleSuggestion.build {}
        }

        // Apply quality filters.
        val suffixText = document.getText(com.intellij.openapi.util.TextRange(offset, document.textLength))
        val prefixLastLine = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))

        if (shouldDiscardSuggestion(suggestion, suffixText, prefixLastLine)) {
            return InlineCompletionSingleSuggestion.build {}
        }

        // Schedule cache warming with current input_extra.
        try {
            val cacheWarmingService = project.service<CacheWarmingService>()
            cacheWarmingService.scheduleWarmup(infillRequest.inputExtra)
        } catch (_: Exception) {
            // Cache warming failure should not block completion.
        }

        // Return the suggestion as gray text.
        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(suggestion))
        }
    }
}
