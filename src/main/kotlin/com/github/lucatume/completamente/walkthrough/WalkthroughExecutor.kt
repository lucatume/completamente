package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.services.escapePosixPath
import com.github.lucatume.completamente.services.renderFileWindow

/**
 * The DTO crossing the EDT/pooled-thread boundary. Captured on the EDT before the agent is
 * dispatched to a background thread; from this point on, the agent's view of the file is
 * frozen at this snapshot — even if the user edits the document while the agent runs.
 *
 * Indexing on this DTO is **1-indexed** for the line/col fields because the prompt envelope
 * renders them verbatim and the LLM wire format is 1-indexed. The action is responsible for
 * converting from IntelliJ's 0-indexed `Document` API at capture time.
 */
data class WalkthroughRequest(
    val prompt: String,
    val filePath: String,
    val language: String,
    val fileContent: String,
    val selectionStart: Int,        // offset in fileContent
    val selectionEnd: Int,          // offset in fileContent
    val startLine: Int,             // 1-indexed (wire format)
    val startCol: Int,              // 1-indexed (wire format)
    val endLine: Int,               // 1-indexed (wire format)
    val endCol: Int                 // 1-indexed, end-column-exclusive (wire format)
) {
    val selectionText: String get() = fileContent.substring(selectionStart, selectionEnd)
}

/**
 * Builds the agent prompt and (in a later task) invokes the configured CLI.
 *
 * Mirrors `Order89Executor`'s shape: prompt envelope built from a frozen document snapshot,
 * file content windowed via the shared `renderFileWindow`, agent invoked via the shared
 * shell-launching primitives in `services/AgentProcess.kt`.
 */
object WalkthroughExecutor {

    fun buildPrompt(request: WalkthroughRequest): String {
        val (before, after) = renderFileWindow(
            request.fileContent,
            request.selectionStart,
            request.selectionEnd
        )
        val selection = request.selectionText
        return buildString {
            appendLine("<WalkthroughPrompt>")
            appendLine("You are a code walkthrough agent. You receive a file with a marked selection range")
            appendLine("and an instruction. You output a guided walkthrough of the code as a single")
            appendLine("<Walkthrough>...</Walkthrough> block.")
            appendLine()
            appendLine("<WalkthroughRules>")
            appendLine("- Output exactly one <Walkthrough> ... </Walkthrough> block. No prose before or after.")
            appendLine("- Each <Step> MUST have:")
            appendLine("    - file=\"<project-relative path, POSIX separators>\"")
            appendLine("    - range=\"startLine:startCol-endLine:endCol\"  (1-indexed; end column is exclusive)")
            appendLine("- <Narration> is optional. Omit the element if the step shows code only.")
            appendLine("- Output at least one <Step>.")
            appendLine("- Steps are presented to the user in document order. The first <Step> should anchor")
            appendLine("  the user at the code they invoked the walkthrough on.")
            appendLine("- Only reference files inside the project. If you need to mention a file outside the")
            appendLine("  project (an SDK, a library, a system header), describe it in the narration text —")
            appendLine("  do NOT use it as a step's file attribute.")
            appendLine("- Do NOT modify any file.")
            appendLine("- Treat the <WalkthroughFileContent> block below as authoritative for the invoked")
            appendLine("  file. The on-disk copy may be stale (the IDE may have unsaved changes).")
            appendLine("</WalkthroughRules>")
            appendLine()
            appendLine("Language: ${request.language}")
            appendLine("File: ${escapePosixPath(request.filePath)}")
            appendLine("Selection: ${request.startLine}:${request.startCol}-${request.endLine}:${request.endCol}  (1-indexed; end column is exclusive)")
            appendLine()
            appendLine("<WalkthroughInstruction>")
            appendLine(request.prompt)
            appendLine("</WalkthroughInstruction>")
            appendLine()
            appendLine("<WalkthroughFileContent>")
            append(before)
            append("<WalkthroughUserSelection>")
            append(selection)
            append("</WalkthroughUserSelection>")
            append(after)
            // Always force a line break before the closing tag so the closing tag never trails
            // the user-selection markers on the same line — matters for empty/short snapshots.
            if (length > 0 && this[length - 1] != '\n') appendLine()
            appendLine("</WalkthroughFileContent>")
            appendLine()
            appendLine("REMINDER: Output ONLY the <Walkthrough> block. Keep file= attributes inside the project.")
            appendLine("</WalkthroughPrompt>")
        }
    }
}
