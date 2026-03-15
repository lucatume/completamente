package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.Settings

/**
 * All inputs needed to compose an InfillRequest.
 */
data class CompletionContext(
    val filePath: String,
    val fileContent: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val structureFiles: List<InfillExtraChunk>,
    val ringChunks: List<Chunk>,
    val settings: Settings
)

/**
 * Composes a ready-to-send InfillRequest from all available context.
 *
 * Orchestrates:
 * 1. buildContext() -> FileContext (whole file or windowed)
 * 2. filterRingChunks() -> filtered ring chunks with similarity eviction
 * 3. allocateBudget() -> what fits in the token budget
 * 4. Assembles InfillRequest with all fields populated
 */
fun composeInfillRequest(ctx: CompletionContext): InfillRequest {
    // 1. Build file context (whole file or windowed based on token count)
    // Reserve roughly 1/3 of the context window for the current file content;
    // the remaining 2/3 is left for extra context chunks and predicted output.
    val maxFileTokens = ctx.settings.contextSize / 3
    val fileContext = buildContext(
        ctx.fileContent,
        ctx.cursorLine,
        ctx.cursorColumn,
        maxFileTokens = maxFileTokens
    )

    // 2. Build cursor context string for similarity filtering:
    //    last ~64 lines of inputPrefix + prompt
    val prefixLines = fileContext.inputPrefix.lines()
    val last64 = prefixLines.takeLast(64).joinToString("\n")
    val cursorContext = if (last64.isEmpty()) fileContext.prompt else last64 + "\n" + fileContext.prompt

    // 3. Exclude ring chunks that belong to the current file (stale content),
    //    then filter the rest by similarity to cursor context.
    val nonSelfRingChunks = ctx.ringChunks.filter { it.filename != ctx.filePath }
    val filteredRingChunks = filterRingChunks(nonSelfRingChunks, cursorContext, similarityThreshold = 0.5)

    // 4. Allocate budget across file context, structure files, and ring chunks
    val budget = allocateBudget(
        fileContext,
        ctx.structureFiles,
        filteredRingChunks,
        contextSize = ctx.settings.contextSize,
        nPredict = ctx.settings.nPredict
    )

    // 5. Build input_extra: structure chunks first, then ring chunks
    val inputExtra = budget.structureChunks + budget.ringChunks

    // 6. Assemble and return InfillRequest
    return InfillRequest(
        inputPrefix = fileContext.inputPrefix,
        inputSuffix = fileContext.inputSuffix,
        prompt = fileContext.prompt,
        nIndent = fileContext.nIndent,
        inputExtra = inputExtra,
        nPredict = ctx.settings.nPredict
    )
}
