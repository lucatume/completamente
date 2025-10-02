package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings
import com.intellij.openapi.vfs.VirtualFile
import kotlin.random.Random

/**
 * Compute how similar two chunks of text are.
 * Returns a value between 0.0 (no similarity) and 1.0 (high similarity).
 *
 * Translation of llama.vim s:chunk_sim function.
 *
 * @param c0 The first chunk as a list of lines
 * @param c1 The second chunk as a list of lines
 * @return Similarity score between 0.0 and 1.0
 */
fun chunkSim(c0: List<String>, c1: List<String>): Double {
    val lines0 = c0.size
    val lines1 = c1.size

    if (lines0 == 0 && lines1 == 0) {
        return 1.0
    }
    if (lines0 == 0 || lines1 == 0) {
        return 0.0
    }

    var common = 0
    for (line0 in c0) {
        for (line1 in c1) {
            if (line0 == line1) {
                common += 1
                break
            }
        }
    }

    return 2.0 * common / (lines0 + lines1)
}

/**
 * Pick a random chunk of size [Settings.ringChunkSize] from the provided text and queue it for processing.
 *
 * Translation of llama.vim s:pick_chunk function.
 *
 * @param text The text lines to pick a chunk from
 * @param doEvict If true, evict chunks that are very similar to the new one; otherwise return early if similar exists
 * @param settings The settings containing ring configuration
 * @param ringChunks The list of processed chunks
 * @param ringQueued The list of queued chunks waiting to be processed
 * @param filename The current filename
 * @return The number of chunks evicted during this operation
 */
fun pickChunk(
    text: List<String>,
    doEvict: Boolean,
    settings: Settings,
    ringChunks: MutableList<Chunk>,
    ringQueued: MutableList<Chunk>,
    filename: String
): Int {
    // If the extra context option is disabled - do nothing
    if (settings.ringNChunks <= 0) {
        return 0
    }

    // Don't pick very small chunks
    if (text.size < 3) {
        return 0
    }

    // Select chunk: if text is smaller than chunk size, use all; otherwise pick a random portion
    val chunk: List<String> = if (text.size + 1 < settings.ringChunkSize) {
        text
    } else {
        val halfChunkSize = settings.ringChunkSize / 2
        val l0 = Random.nextInt(0, maxOf(1, text.size - halfChunkSize + 1))
        val l1 = minOf(l0 + halfChunkSize, text.size)
        text.subList(l0, l1)
    }

    val chunkStr = chunk.joinToString("\n") + "\n"

    // Check if this chunk is already added (exact match)
    for (existingChunk in ringChunks) {
        if (existingChunk.text == chunkStr) {
            return 0
        }
    }
    for (queuedChunk in ringQueued) {
        if (queuedChunk.text == chunkStr) {
            return 0
        }
    }

    var evictCount = 0

    // Evict queued chunks that are very similar to the new one
    val queuedIterator = ringQueued.listIterator(ringQueued.size)
    while (queuedIterator.hasPrevious()) {
        val queuedChunk = queuedIterator.previous()
        val queuedLines = queuedChunk.text.trimEnd('\n').split("\n")
        if (chunkSim(queuedLines, chunk) > 0.9) {
            if (doEvict) {
                queuedIterator.remove()
                evictCount += 1
            } else {
                return 0
            }
        }
    }

    // Also evict from ringChunks
    val chunksIterator = ringChunks.listIterator(ringChunks.size)
    while (chunksIterator.hasPrevious()) {
        val existingChunk = chunksIterator.previous()
        val existingLines = existingChunk.text.trimEnd('\n').split("\n")
        if (chunkSim(existingLines, chunk) > 0.9) {
            if (doEvict) {
                chunksIterator.remove()
                evictCount += 1
            } else {
                return 0
            }
        }
    }

    // If ringQueued is at max size, remove the oldest entry
    if (ringQueued.size == settings.maxQueuedChunks) {
        ringQueued.removeAt(0)
    }

    // Add the new chunk
    ringQueued.add(
        Chunk(
            text = chunkStr,
            time = System.currentTimeMillis(),
            filename = filename
        )
    )

    return evictCount
}

/**
 * Pick a chunk from a file around the cursor position.
 * Used when opening, closing, or saving a file.
 *
 * Translation of the llama.vim logic from BufEnter, BufLeave, and BufWritePost autocmds.
 *
 * @param file The virtual file to pick a chunk from
 * @param cursorLine The current cursor line (1-indexed). If null, uses the center of the file.
 * @param isModified Whether the file has unsaved modifications
 * @param settings The settings containing ring configuration
 * @param ringChunks The list of processed chunks
 * @param ringQueued The list of queued chunks waiting to be processed
 * @return The number of chunks evicted during this operation, or -1 if skipped
 */
fun pickChunkFromFile(
    file: VirtualFile,
    cursorLine: Int?,
    isModified: Boolean,
    settings: Settings,
    ringChunks: MutableList<Chunk>,
    ringQueued: MutableList<Chunk>
): Int {
    // Skip if file has pending modifications (noMod=true in vim)
    if (isModified) {
        return -1
    }

    // Skip if file is not readable
    if (!file.isValid) {
        return -1
    }

    // Read file content
    val content = try {
        String(file.contentsToByteArray(), file.charset)
    } catch (_: Exception) {
        return -1
    }

    val allLines = content.lines()
    val totalLines = allLines.size

    if (totalLines == 0) {
        return -1
    }

    // Determine cursor position (1-indexed, as in vim)
    // If no cursor provided, use center of file
    val effectiveCursorLine = cursorLine ?: ((totalLines / 2) + 1)

    // Extract lines around cursor: from max(1, cursor - ringChunkSize/2) to min(cursor + ringChunkSize/2, totalLines)
    // Convert to 0-indexed for Kotlin list access
    val halfChunkSize = settings.ringChunkSize / 2
    val startLine = maxOf(0, effectiveCursorLine - 1 - halfChunkSize)
    val endLine = minOf(totalLines, effectiveCursorLine - 1 + halfChunkSize + 1)

    val linesAroundCursor = allLines.subList(startLine, endLine)

    // Call pickChunk with noMod=true (already checked), doEvict=true
    return pickChunk(
        text = linesAroundCursor,
        doEvict = true,
        settings = settings,
        ringChunks = ringChunks,
        ringQueued = ringQueued,
        filename = file.path
    )
}

/**
 * Pick a chunk from arbitrary text content (e.g., yanked/copied text).
 * Used when text is copied to clipboard.
 *
 * Translation of the llama.vim logic from TextYankPost autocmd:
 * `autocmd TextYankPost * if v:event.operator ==# 'y' | call s:pick_chunk(v:event.regcontents, v:false, v:true) | endif`
 *
 * @param text The text content to pick a chunk from
 * @param name A name/identifier for this text (e.g., "clipboard", source filename)
 * @param settings The settings containing ring configuration
 * @param ringChunks The list of processed chunks
 * @param ringQueued The list of queued chunks waiting to be processed
 * @return The number of chunks evicted during this operation, or -1 if skipped
 */
fun pickChunkFromText(
    text: String,
    name: String,
    settings: Settings,
    ringChunks: MutableList<Chunk>,
    ringQueued: MutableList<Chunk>
): Int {
    // Split text into lines (matching vim's regcontents which is a list of lines)
    val lines = text.lines()

    // Call pickChunk with noMod=false (no modification check for raw text), doEvict=true
    return pickChunk(
        text = lines,
        doEvict = true,
        settings = settings,
        ringChunks = ringChunks,
        ringQueued = ringQueued,
        filename = name
    )
}
