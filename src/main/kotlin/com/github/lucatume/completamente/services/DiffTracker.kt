package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.completion.estimateTokens
import com.github.lucatume.completamente.completion.truncateDiffText
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.Alarm

// --- Constants ---

const val COALESCE_LINE_SPAN = 8
const val PAUSE_THRESHOLD_MS = 1_000L
const val DEFAULT_MAX_RECENT_DIFFS = 10

// --- Data types ---

data class StoredDiff(
    val filePath: String,
    val timestamp: Long,
    val beforeSnapshot: CharSequence,
    val afterSnapshot: CharSequence,
    val startLine: Int,
    val endLine: Int
)

class PendingEdit(
    val filePath: String,
    val document: Document,
    val beforeSnapshot: CharSequence,
    var startLine: Int,
    var endLine: Int,
    var lastEditTime: Long,
) {
    var latestSnapshot: CharSequence = beforeSnapshot
}

data class DiffEntry(
    val filePath: String,
    val original: String,
    val updated: String,
    val estimatedTokens: Int = 0
)

// --- Pure functions ---

fun stripDummyIdentifier(text: CharSequence): String {
    return text.toString()
        .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
        .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
}

fun shouldCoalesce(pending: PendingEdit, editLine: Int, lineSpan: Int = COALESCE_LINE_SPAN): Boolean {
    return kotlin.math.abs(editLine - pending.startLine) <= lineSpan ||
            kotlin.math.abs(editLine - pending.endLine) <= lineSpan
}

fun computeDiffEntries(diffs: List<StoredDiff>): List<DiffEntry> {
    return diffs.mapNotNull { diff ->
        val beforeLines = diff.beforeSnapshot.toString().lines()
        val afterLines = diff.afterSnapshot.toString().lines()

        // Find first differing line from the top.
        var firstDiff = 0
        while (firstDiff < beforeLines.size && firstDiff < afterLines.size
            && beforeLines[firstDiff] == afterLines[firstDiff]
        ) {
            firstDiff++
        }

        // Find first differing line from the bottom.
        var beforeBottom = beforeLines.size - 1
        var afterBottom = afterLines.size - 1
        while (beforeBottom >= firstDiff && afterBottom >= firstDiff
            && beforeLines[beforeBottom] == afterLines[afterBottom]
        ) {
            beforeBottom--
            afterBottom--
        }

        // No actual diff found.
        if (firstDiff > beforeBottom && firstDiff > afterBottom) return@mapNotNull null

        val original = if (firstDiff <= beforeBottom) {
            beforeLines.subList(firstDiff, beforeBottom + 1).joinToString("\n")
        } else {
            ""
        }

        val updated = if (firstDiff <= afterBottom) {
            afterLines.subList(firstDiff, afterBottom + 1).joinToString("\n")
        } else {
            ""
        }

        // Skip diffs where both sides are empty/blank (e.g. trailing whitespace-only changes).
        if (original.isBlank() && updated.isBlank()) return@mapNotNull null

        val truncatedOriginal = truncateDiffText(original)
        val truncatedUpdated = truncateDiffText(updated)
        val diffText = "<|file_sep|>${diff.filePath}.diff\noriginal:\n$truncatedOriginal\nupdated:\n$truncatedUpdated"

        DiffEntry(
            filePath = diff.filePath,
            original = truncatedOriginal,
            updated = truncatedUpdated,
            estimatedTokens = estimateTokens(diffText)
        )
    }
}

// --- Service ---

@Service(Service.Level.PROJECT)
class DiffTracker(private val project: Project) : DocumentListener, Disposable {
    private val lock = Any()
    private val storedDiffs: ArrayDeque<StoredDiff> = ArrayDeque()
    private val pendingEdits: MutableMap<String, PendingEdit> = mutableMapOf()
    private val originalSnapshots: MutableMap<String, String> = mutableMapOf()
    var ignoreNextChange: Boolean = false

    private val maxDiffs: Int
        get() = SettingsState.getInstance().maxRecentDiffs

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        schedulePauseCheck()
    }

    private fun schedulePauseCheck() {
        alarm.addRequest({
            checkPausedEdits()
            schedulePauseCheck()
        }, PAUSE_THRESHOLD_MS.toInt())
    }

    private fun checkPausedEdits() {
        val now = System.currentTimeMillis()
        val toFinalize = pendingEdits.entries.filter { (_, pending) ->
            now - pending.lastEditTime > PAUSE_THRESHOLD_MS
        }.map { it.key }

        for (filePath in toFinalize) {
            finalizePendingEdit(filePath)
        }
    }

    override fun beforeDocumentChange(event: DocumentEvent) {
        if (ignoreNextChange) return

        val filePath = resolveFilePath(event) ?: return
        val pending = pendingEdits[filePath]

        if (pending == null) {
            val editLine = event.document.getLineNumber(event.offset)
            pendingEdits[filePath] = PendingEdit(
                filePath = filePath,
                document = event.document,
                beforeSnapshot = stripDummyIdentifier(event.document.immutableCharSequence),
                startLine = editLine,
                endLine = editLine,
                lastEditTime = System.currentTimeMillis()
            )
        } else {
            pending.latestSnapshot = stripDummyIdentifier(event.document.immutableCharSequence)
        }
    }

    override fun documentChanged(event: DocumentEvent) {
        if (ignoreNextChange) {
            ignoreNextChange = false
            return
        }

        val filePath = resolveFilePath(event) ?: return
        val editLine = event.document.getLineNumber(event.offset)
        val pending = pendingEdits[filePath]

        if (pending != null) {
            if (shouldCoalesce(pending, editLine)) {
                pending.startLine = minOf(pending.startLine, editLine)
                pending.endLine = maxOf(pending.endLine, editLine)
                pending.lastEditTime = System.currentTimeMillis()
            } else {
                // Distant edit: finalize the existing pending using the pre-edit snapshot,
                // then start a new pending whose beforeSnapshot is also the pre-edit state.
                val preEditSnapshot = pending.latestSnapshot
                storeDiff(pending, preEditSnapshot)
                pendingEdits[filePath] = PendingEdit(
                    filePath = filePath,
                    document = event.document,
                    beforeSnapshot = preEditSnapshot,
                    startLine = editLine,
                    endLine = editLine,
                    lastEditTime = System.currentTimeMillis()
                )
            }
        }
        // If no pending exists, beforeDocumentChange should have created one.
        // Defensive: ignore if somehow missing.
    }

    fun finalizePendingEdit(filePath: String) {
        val pending = pendingEdits.remove(filePath) ?: return
        storeDiff(pending, stripDummyIdentifier(pending.document.immutableCharSequence))
    }

    private fun storeDiff(pending: PendingEdit, afterSnapshot: CharSequence) {
        val diff = StoredDiff(
            filePath = pending.filePath,
            timestamp = System.currentTimeMillis(),
            beforeSnapshot = pending.beforeSnapshot,
            afterSnapshot = afterSnapshot,
            startLine = pending.startLine,
            endLine = pending.endLine
        )
        synchronized(lock) {
            storedDiffs.addLast(diff)
            while (storedDiffs.size > maxDiffs) {
                storedDiffs.removeFirst()
            }
        }
    }

    fun snapshotOriginalContent(filePath: String, content: String) {
        synchronized(lock) {
            if (!originalSnapshots.containsKey(filePath)) {
                originalSnapshots[filePath] = content
            }
        }
    }

    fun getOriginalContent(filePath: String): String {
        return synchronized(lock) { originalSnapshots[filePath] ?: "" }
    }

    fun getRecentDiffs(): List<DiffEntry> {
        val snapshot = synchronized(lock) { storedDiffs.toList() }
        return computeDiffEntries(snapshot)
    }

    private fun resolveFilePath(event: DocumentEvent): String? {
        val virtualFile = FileDocumentManager.getInstance().getFile(event.document) ?: return null
        if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return null
        return virtualFile.path
    }

    override fun dispose() {
        synchronized(lock) {
            storedDiffs.clear()
            originalSnapshots.clear()
        }
        pendingEdits.clear()
    }
}
