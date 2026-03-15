package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk

data class BudgetAllocation(
    val fileContext: FileContext,
    val structureChunks: List<InfillExtraChunk>,
    val ringChunks: List<InfillExtraChunk>,
    val totalEstimatedTokens: Int
)

/**
 * Allocates the token budget across file context, structure files, and ring chunks.
 * Structure files and ring chunks are added one by one in order until the budget is exhausted.
 * A chunk that exceeds remaining budget is skipped (try next smaller one).
 */
fun allocateBudget(
    fileContext: FileContext,
    structureFiles: List<InfillExtraChunk>,
    ringChunks: List<InfillExtraChunk>,
    contextSize: Int = 32768,
    nPredict: Int = 128,
    overheadTokens: Int = 20
): BudgetAllocation {
    val promptBudget = contextSize - nPredict - overheadTokens
    val fileTokens = estimateTokens(fileContext.inputPrefix + fileContext.prompt + fileContext.inputSuffix)
    var remaining = promptBudget - fileTokens

    if (remaining <= 0) {
        return BudgetAllocation(
            fileContext = fileContext,
            structureChunks = emptyList(),
            ringChunks = emptyList(),
            totalEstimatedTokens = fileTokens
        )
    }

    val addedStructure = mutableListOf<InfillExtraChunk>()
    for (chunk in structureFiles) {
        // Account for the <|file_sep|> token the server prepends to each input_extra chunk.
        val chunkTokens = estimateTokens("<|file_sep|>" + chunk.filename + "\n" + chunk.text)
        if (chunkTokens <= remaining) {
            addedStructure.add(chunk)
            remaining -= chunkTokens
        }
    }

    val addedRing = mutableListOf<InfillExtraChunk>()
    for (chunk in ringChunks) {
        // Account for the <|file_sep|> token the server prepends to each input_extra chunk.
        val chunkTokens = estimateTokens("<|file_sep|>" + chunk.filename + "\n" + chunk.text)
        if (chunkTokens <= remaining) {
            addedRing.add(chunk)
            remaining -= chunkTokens
        }
    }

    return BudgetAllocation(
        fileContext = fileContext,
        structureChunks = addedStructure,
        ringChunks = addedRing,
        totalEstimatedTokens = promptBudget - remaining
    )
}

/**
 * Filters ring chunks by similarity to cursor context, converting Chunk to InfillExtraChunk.
 * Chunks with similarity > threshold to the cursor context are excluded.
 */
fun filterRingChunks(
    ringChunks: List<Chunk>,
    cursorContext: String,
    similarityThreshold: Double = 0.5
): List<InfillExtraChunk> {
    val cursorLines = cursorContext.lines()
    return ringChunks
        .filter { chunk ->
            val sim = chunkSim(chunk.text.lines(), cursorLines)
            sim <= similarityThreshold
        }
        .map { chunk -> InfillExtraChunk(filename = chunk.filename, text = chunk.text) }
        // Sort by filename for stable input_extra ordering across requests, enabling optimal KV cache prefix reuse.
        .sortedBy { it.filename }
}
