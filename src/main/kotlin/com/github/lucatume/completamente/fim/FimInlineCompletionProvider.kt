package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.completion.CompletionContext
import com.github.lucatume.completamente.completion.IndentStyle
import com.github.lucatume.completamente.completion.InfillClient
import com.github.lucatume.completamente.completion.InfillExtraChunk
import com.github.lucatume.completamente.completion.buildStructureChunks
import com.github.lucatume.completamente.completion.composeInfillRequest
import com.github.lucatume.completamente.completion.estimateTokens
import com.github.lucatume.completamente.completion.reindentSuggestion
import com.github.lucatume.completamente.completion.shouldDiscardSuggestion
import com.github.lucatume.completamente.completion.trimCompletion
import com.github.lucatume.completamente.completion.shouldSuppressAutoTrigger
import com.github.lucatume.completamente.services.DebugLog
import com.github.lucatume.completamente.services.CacheWarmingService
import com.github.lucatume.completamente.services.Chunk
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
import com.intellij.application.options.CodeStyle
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
        val fimStart = System.nanoTime()

        // All editor/document/PSI access must happen inside a read action.
        // Capture everything we need from the editor state in one read action.
        data class EditorSnapshot(
            val offset: Int,
            val cursorLine: Int,
            val cursorColumn: Int,
            val lineStart: Int,
            val fileContent: String,
            val filePath: String,
            val structureChunks: List<InfillExtraChunk>,
            val ringChunks: List<Chunk>,
            val cursorLineIndent: String,
            val indentStyle: IndentStyle
        )

        val snapshot = DebugLog.timed("FIM snapshot") { readAction {
            val offset = editor.caretModel.offset
            val cursorLine = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(cursorLine)
            val cursorColumn = offset - lineStart
            val fileContent = document.text
            val filePath = psiFile.virtualFile?.path ?: psiFile.name

            // Build structure chunks (requires read action for PSI access).
            val maxFileTokens = settings.contextSize / 3
            val wholeFile = estimateTokens(fileContent) <= maxFileTokens
            val structureChunks = buildStructureChunks(psiFile, wholeFile)

            // Get ring chunks from the project service.
            val chunksRingBuffer = project.service<ChunksRingBuffer>()
            val ringChunks = chunksRingBuffer.getRingChunks().toList()

            // Capture indent style for suggestion reindentation.
            val lineEnd = fileContent.indexOf('\n', lineStart).let { if (it < 0) fileContent.length else it }
            val currentLineText = fileContent.substring(lineStart, lineEnd)
            val cursorLineIndent = currentLineText.takeWhile { it == ' ' || it == '\t' }
            val indentOptions = CodeStyle.getIndentOptions(psiFile)
            val indentStyle = IndentStyle(
                useTabs = indentOptions.USE_TAB_CHARACTER,
                indentSize = indentOptions.INDENT_SIZE
            )

            EditorSnapshot(
                offset = offset,
                cursorLine = cursorLine,
                cursorColumn = cursorColumn,
                lineStart = lineStart,
                fileContent = fileContent,
                filePath = filePath,
                structureChunks = structureChunks,
                ringChunks = ringChunks,
                cursorLineIndent = cursorLineIndent,
                indentStyle = indentStyle
            )
        } }

        DebugLog.log("FIM start: ${snapshot.filePath}:${snapshot.cursorLine}:${snapshot.cursorColumn}")

        coroutineContext.ensureActive()

        // Compose the infill request (pure function, no read action needed).
        val ctx = CompletionContext(
            filePath = snapshot.filePath,
            fileContent = snapshot.fileContent,
            cursorLine = snapshot.cursorLine,
            cursorColumn = snapshot.cursorColumn,
            structureFiles = snapshot.structureChunks,
            ringChunks = snapshot.ringChunks,
            settings = settings
        )
        val infillRequest = DebugLog.timed("FIM compose") { composeInfillRequest(ctx) }

        coroutineContext.ensureActive()

        // Send the request on IO dispatcher (respects coroutine cancellation via thread interrupt).
        val response = try {
            DebugLog.timed("FIM HTTP request") {
                withContext(Dispatchers.IO) {
                    val client = InfillClient(settings.serverUrl)
                    client.sendCompletion(infillRequest)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DebugLog.log("FIM HTTP error: ${e.message}")
            return InlineCompletionSingleSuggestion.build {}
        }

        coroutineContext.ensureActive()

        val suggestion = response.content
        if (suggestion.isEmpty()) {
            DebugLog.log("FIM empty response")
            return InlineCompletionSingleSuggestion.build {}
        }

        // Apply quality filters — use snapshot values captured earlier (no read action needed).
        val suffixText = snapshot.fileContent.substring(snapshot.offset)
        val prefixLastLine = snapshot.fileContent.substring(snapshot.lineStart, snapshot.offset)

        if (shouldDiscardSuggestion(suggestion, suffixText, prefixLastLine)) {
            DebugLog.log("FIM suggestion discarded by quality filter")
            return InlineCompletionSingleSuggestion.build {}
        }

        // Trim leading indent overlap on line 0 and trailing whitespace artifacts.
        val trimmed = trimCompletion(suggestion, snapshot.cursorColumn)
        if (trimmed.isEmpty()) {
            DebugLog.log("FIM suggestion empty after trimming")
            return InlineCompletionSingleSuggestion.build {}
        }

        // Schedule cache warming with current input_extra.
        try {
            val cacheWarmingService = project.service<CacheWarmingService>()
            cacheWarmingService.scheduleWarmup(infillRequest.inputExtra)
        } catch (_: Exception) {
            // Cache warming failure should not block completion.
        }

        // Reindent multi-line suggestions to match project code style.
        val reindented = reindentSuggestion(trimmed, snapshot.cursorLineIndent, snapshot.indentStyle)

        val totalMs = (System.nanoTime() - fimStart) / 1_000_000
        DebugLog.log("FIM complete: ${reindented.length} chars, total ${totalMs}ms")

        // Return the suggestion as gray text.
        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(reindented))
        }
    }
}
