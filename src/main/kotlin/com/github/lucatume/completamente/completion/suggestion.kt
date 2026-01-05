package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.BackgroundJobs
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.ChunksRingBuffer
import com.github.lucatume.completamente.services.Services
import com.github.lucatume.completamente.services.Settings
import com.github.lucatume.completamente.services.SuggestionCache
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import io.ktor.client.HttpClient
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs

data class SuggestionResult(
    val suggestion: StringSuggestion,
    val prev: List<String>?,
    val indentLast: Int
)

suspend fun getSuggestionPure(
    services: Services,
    request: InlineCompletionRequest,
    prev: List<String>?,
    indentLast: Int,
    lastFile: String?,
    lastLine: Int?
): SuggestionResult {
    val currentFile = request.editor.virtualFile.canonicalPath
    val currentLine = request.editor.caretModel.logicalPosition.line
    val extraContext = services.chunksRingBuffer.getRingChunks()

    lastLine.let { _ ->
        // Gather extra context chunks when cursor has moved >32 lines
        // Translation of llama.vim lines 731-741
        if (currentFile == lastFile && lastLine != null && abs(lastLine - currentLine) >= 32) {
            val document = request.document
            val maxY = document.lineCount
            val filename = request.editor.virtualFile.path

            // Helper to extract lines in range (0-indexed, exclusive end)
            fun getLinesInRange(startLine: Int, endLine: Int): List<String> {
                val actualStart = maxOf(0, startLine)
                val actualEnd = minOf(maxY, endLine)
                if (actualStart >= actualEnd) return emptyList()

                return (actualStart until actualEnd).map { lineNum ->
                    val start = document.getLineStartOffset(lineNum)
                    val end = document.getLineEndOffset(lineNum)
                    document.getText(TextRange(start, end))
                }
            }

            // Pick prefix chunk: lines from (currentLine - ringScope) to (currentLine - nPrefix)
            val prefixStartLine = currentLine - services.settings.ringScope
            val prefixEndLine = currentLine - services.settings.nPrefix
            val prefixLines = getLinesInRange(prefixStartLine, prefixEndLine)

            pickChunk(
                text = prefixLines,
                doEvict = false,
                settings = services.settings,
                ringChunks = services.chunksRingBuffer.getRingChunks(),
                ringQueued = services.chunksRingBuffer.getRingQueued(),
                filename = filename
            )

            // Pick suffix chunk: lines from (currentLine + nSuffix) to (currentLine + nSuffix + ringChunkSize)
            val suffixStartLine = currentLine + services.settings.nSuffix
            val suffixEndLine = currentLine + services.settings.nSuffix + services.settings.ringChunkSize
            val suffixLines = getLinesInRange(suffixStartLine, suffixEndLine)

            pickChunk(
                text = suffixLines,
                doEvict = false,
                settings = services.settings,
                ringChunks = services.chunksRingBuffer.getRingChunks(),
                ringQueued = services.chunksRingBuffer.getRingQueued(),
                filename = filename
            )
        }
    }

    // Build the local context from the request.
    val localContext = buildLocalContext(
        request = request,
        settings = services.settings,
        prev = null,
        indentLast = indentLast
    )

    // Port of llama.vim lines 599-601: Check max_line_suffix threshold
    // Don't auto-trigger completion if there are too many characters to the right of cursor
    if (localContext.lineCurSuffix.length > services.settings.maxLineSuffix) {
        return SuggestionResult(
            StringSuggestion(""),
            prev,
            indentLast
        )
    }

    var updatedPrev = prev
    var updatedIndentLast = indentLast

    // Try to get a cached suggestion
    val cacheResult = tryGetCachedSuggestion(services.cache, localContext)

    if (cacheResult != null) {
        // Extract the content for display
        val content = cacheResult.trimmedContent ?: extractContentFromResponse(cacheResult.raw)

        val rendered = fimRender(localContext, content ?: "", request)
        if (rendered != null) {
            // Update tracking variables for next coroutine call
            updatedPrev = content?.split("\n")
            updatedIndentLast = localContext.indent
        }

        return SuggestionResult(
            StringSuggestion(rendered ?: ""),
            updatedPrev,
            updatedIndentLast
        )
    }

    // Create a channel for coroutines to communicate with the cache
    val channel = Channel<String>()
    services.backgroundJobs.runWithDebounce({
        val llmSuggestion = fim(
            localContext,
            extraContext,
            services.settings,
            services.cache,
            services.httpClient
        )

        // Send the completion back to the main thread.
        channel.send(llmSuggestion ?: "")
    }, 100)

    val suggestion: String = channel.receive()

    val rendered = fimRender(localContext, suggestion, request)
    if (rendered != null && suggestion.isNotEmpty()) {
        updatedPrev = suggestion.split("\n")
        updatedIndentLast = localContext.indent
    }

    // This is the closest we can get to the llama.vim code:
    //  if s:hint_shown
    //      call llama#fim(l:pos_x, l:pos_y, v:true, s:fim_data['content'], v:true)
    //  endif
    // The suggestion is going to be shown to the user: start a speculative request for the code as if the user
    // had accepted the suggestion.
    services.backgroundJobs.runWithDebounce({
        val speculativeContext = buildLocalContext(
            request = request,
            settings = services.settings,
            prev = updatedPrev,
            indentLast = localContext.indent
        )

        // Start a speculative request to get the completion as if the user had accepted the suggestion.
        fim(
            speculativeContext,
            extraContext,
            services.settings,
            services.cache,
            services.httpClient
        )
    }, 100)

    return SuggestionResult(
        StringSuggestion(rendered ?: ""),
        updatedPrev,
        updatedIndentLast
    )
}
