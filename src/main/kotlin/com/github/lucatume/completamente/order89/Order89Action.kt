package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.services.DebugLog
import com.github.lucatume.completamente.completion.collectReferencedFiles
import com.github.lucatume.completamente.completion.surfaceExtract
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.openapi.editor.colors.EditorColors
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.KeyStroke
import javax.swing.Timer

internal fun statusLineColors(editor: Editor): Pair<Color, Color> {
    val scheme = editor.colorsScheme
    val popColor = scheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
        ?: scheme.defaultForeground
    val defaultFg = scheme.defaultForeground
    return Pair(popColor, defaultFg)
}

internal fun shimmerColors(editor: Editor): Pair<Color, Color> {
    val bg = editor.colorsScheme.defaultBackground
    val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) / 255.0
    return if (luminance < 0.5) {
        Pair(Color(180, 130, 255), Color(230, 200, 255))
    } else {
        Pair(Color(120, 60, 200), Color(170, 120, 255))
    }
}

internal fun interpolateColor(base: Color, peak: Color, factor: Float): Color {
    val f = factor.coerceIn(0f, 1f)
    return Color(
        (base.red + (peak.red - base.red) * f).toInt(),
        (base.green + (peak.green - base.green) * f).toInt(),
        (base.blue + (peak.blue - base.blue) * f).toInt()
    )
}

internal fun truncatePrompt(prompt: String, maxLength: Int = 60): String {
    val collapsed = prompt.replace('\n', ' ').replace('\r', ' ').trim()
    if (collapsed.length <= maxLength) return collapsed
    return collapsed.substring(0, maxLength) + "..."
}

internal fun formatPromptLines(prompt: String, maxWidth: Int = 80): List<String> {
    val words = prompt.trim().split(Regex("\\s+"))
    if (words.isEmpty() || (words.size == 1 && words[0].isEmpty())) return listOf("")
    val lines = mutableListOf<String>()
    val current = StringBuilder(words[0])
    for (i in 1 until words.size) {
        if (current.length + 1 + words[i].length > maxWidth) {
            lines.add(current.toString())
            current.clear().append(words[i])
        } else {
            current.append(' ').append(words[i])
        }
    }
    lines.add(current.toString())
    return lines
}

data class Order89StatusDisplay(
    val range: RangeMarker,
    val symbolRange: RangeMarker,
    val highlighters: MutableList<RangeHighlighter>,
    val shimmerHighlighters: List<RangeHighlighter>,
    val timer: Timer,
    val shimmerTimer: Timer
)

data class Order89Session(
    val future: Future<Order89Result>,
    val statusDisplay: Order89StatusDisplay?,
    val range: RangeMarker
)

class Order89Action : AnAction() {

    companion object {
        internal val SESSIONS_KEY = Key.create<ArrayDeque<Order89Session>>("order89.sessions")
        private val ESC_ACTION_KEY = Key.create<AnAction>("order89.escAction")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd
        val targetLine = editor.document.getLineNumber(selectionStart)

        val dialog = Order89Dialog(editor.component)
        if (!dialog.showAndWait() || dialog.promptText.isBlank()) return

        val settings = SettingsState.getInstance().toSettings()
        DebugLog.log("Order89 start: file=${psiFile.virtualFile?.path}, selection=$selectionStart-$selectionEnd")

        val contextChunks = DebugLog.timed("Order89 context extraction") { runReadAction<List<ContextChunk>> {
            try {
                val startLine = editor.document.getLineNumber(selectionStart)
                val endLine = editor.document.getLineNumber(selectionEnd)
                val referencedFiles = collectReferencedFiles(psiFile, startLine, endLine)
                referencedFiles.mapNotNull { file ->
                    val extracted = surfaceExtract(project, file)
                    if (extracted != null) {
                        val relativePath = file.path.removePrefix(project.basePath ?: "").removePrefix("/")
                        ContextChunk(relativePath, extracted)
                    } else null
                }
            } catch (_: Exception) {
                emptyList()
            }
        } }
        DebugLog.log("Order89 context: ${contextChunks.size} chunks")

        val filePath = psiFile.virtualFile?.path ?: ""

        // Capture file content BEFORE inserting status lines.
        val request = Order89Request(
            prompt = dialog.promptText,
            filePath = filePath,
            fileContent = editor.document.text,
            language = psiFile.language.id,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            contextChunks = contextChunks
        )

        // Parse tool mode and strip /tools prefix if applicable
        val (cleanPrompt, toolsEnabled) = parseToolsPrefix(dialog.promptText, settings.order89ToolUsage)

        // Rebuild request with cleaned prompt if tools stripped the prefix
        val effectiveRequest = if (cleanPrompt != request.prompt) {
            request.copy(prompt = cleanPrompt)
        } else {
            request
        }

        // Create selection range marker BEFORE inserting status lines.
        val rangeMarker = editor.document.createRangeMarker(selectionStart, selectionEnd)
        rangeMarker.isGreedyToRight = true

        // Insert status lines as real document text above the selection.
        val insertionOffset = editor.document.getLineStartOffset(targetLine)
        val statusDisplay = insertStatusLines(editor, insertionOffset, dialog.promptText)

        val future: Future<Order89Result> = if (toolsEnabled) {
            val toolExecutor: (ToolCall) -> String = { call ->
                when (call.name) {
                    "FileSearch" -> {
                        val query = call.arguments["query"]?.jsonPrimitive?.content ?: ""
                        val caseSensitive = call.arguments["case_sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val path = call.arguments["path"]?.jsonPrimitive?.content
                        runReadAction { FileSearchTool.execute(project, query, caseSensitive, path) }
                    }
                    "DocSearch" -> {
                        val query = call.arguments["query"]?.jsonPrimitive?.content ?: ""
                        val docsets = call.arguments["docsets"]?.jsonPrimitive?.content
                        DocSearchTool.execute(query, docsets)
                    }
                    else -> "Unknown tool: ${call.name}"
                }
            }
            val sharedHttpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
            val completionFn: (String, String) -> String = { body, serverUrl ->
                val httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("$serverUrl/completion"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = sharedHttpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    throw RuntimeException("HTTP ${response.statusCode()}: ${response.body()}")
                }
                response.body()
            }
            val executor = Executors.newSingleThreadExecutor()
            val f = executor.submit(Callable {
                Order89Executor.executeWithTools(
                    effectiveRequest, settings, toolExecutor, completionFn,
                    onStatusUpdate = { update ->
                        ApplicationManager.getApplication().invokeLater({
                            if (!editor.isDisposed) {
                                updateStatusDisplay(editor, statusDisplay, update)
                            }
                        }, ModalityState.defaultModalityState())
                    }
                )
            })
            executor.shutdown()
            f
        } else {
            Order89Executor.execute(effectiveRequest, settings)
        }

        val session = Order89Session(future, statusDisplay, rangeMarker)
        val sessions = editor.getUserData(SESSIONS_KEY) ?: ArrayDeque<Order89Session>().also {
            editor.putUserData(SESSIONS_KEY, it)
        }
        val wasEmpty = sessions.isEmpty()
        sessions.push(session)

        if (wasEmpty) {
            val escAction = Order89EscAction(editor, this)
            escAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                editor.component
            )
            editor.putUserData(ESC_ACTION_KEY, escAction)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = future.get()
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    if (result.success) {
                        // Remove status lines BEFORE the command so the deletion
                        // is truly undo-transparent. Nesting runUndoTransparentAction
                        // inside WriteCommandAction causes IntelliJ to record it in
                        // the same undo group, making status text reappear on undo.
                        removeStatusDisplay(editor, session.statusDisplay)
                        val originalSelection = effectiveRequest.fileContent.substring(
                            effectiveRequest.selectionStart,
                            effectiveRequest.selectionEnd
                        )
                        val finalOutput = Order89Executor.matchTrailingNewlines(originalSelection, result.output)
                        WriteCommandAction.runWriteCommandAction(project, "Order 89", null, {
                            if (!session.range.isValid) return@runWriteCommandAction
                            editor.document.replaceString(session.range.startOffset, session.range.endOffset, finalOutput)
                            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                            val committedPsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                            if (committedPsiFile != null && session.range.isValid) {
                                CodeStyleManager.getInstance(project).reformatText(committedPsiFile, session.range.startOffset, session.range.endOffset)
                            }
                        })
                    } else {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("completamente")
                            .createNotification(result.output, NotificationType.ERROR)
                            .notify(project)
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled by user via ESC.
            } catch (ex: Exception) {
                // Covers HTTP errors, unexpected executor failures, etc.
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("completamente")
                        .createNotification(ex.message ?: "Unknown error", NotificationType.ERROR)
                        .notify(project)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    val deque = editor.getUserData(SESSIONS_KEY)
                    if (deque?.remove(session) == true) {
                        removeStatusDisplay(editor, session.statusDisplay)
                        session.range.dispose()
                    }
                    if (deque?.isEmpty() == true) {
                        unregisterEsc(editor)
                    }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    internal fun unregisterEsc(editor: Editor) {
        editor.getUserData(ESC_ACTION_KEY)?.unregisterCustomShortcutSet(editor.component)
        editor.putUserData(ESC_ACTION_KEY, null)
    }

    /**
     * Stops the timer, removes highlighters, deletes status text from the document (undo-transparently),
     * and disposes the status range. Safe to call when the display has already been cleaned up (idempotent):
     * the timer stop and highlighter removal are no-ops if already done, and the range validity check
     * guards the document deletion.
     */
    internal fun removeStatusDisplay(editor: Editor, display: Order89StatusDisplay?) {
        if (display == null) return
        display.timer.stop()
        display.shimmerTimer.stop()
        display.shimmerHighlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.symbolRange.dispose()
        if (display.range.isValid) {
            ApplicationManager.getApplication().runWriteAction {
                CommandProcessor.getInstance().runUndoTransparentAction {
                    UndoUtil.disableUndoIn(editor.document) {
                        if (display.range.isValid) {
                            editor.document.deleteString(display.range.startOffset, display.range.endOffset)
                        }
                    }
                }
            }
        }
        display.range.dispose()
    }

    internal fun updateStatusDisplay(editor: Editor, display: Order89StatusDisplay?, update: StatusUpdate) {
        if (display == null || !display.range.isValid) return
        // WaitingForModel: leave existing sub-lines (prompt text or previous tool calls) intact.
        if (update is StatusUpdate.WaitingForModel) return
        val doc = editor.document
        val startOffset = display.range.startOffset
        val startLine = doc.getLineNumber(startOffset)
        val firstLineEnd = doc.getLineEndOffset(startLine)
        val firstLineText = doc.getText(TextRange(doc.getLineStartOffset(startLine), firstLineEnd))
        val indent = firstLineText.takeWhile { it == ' ' || it == '\t' }

        // Remove old sub-line highlighters (shimmer handles the first line separately)
        while (display.highlighters.isNotEmpty()) {
            val h = display.highlighters.removeAt(display.highlighters.lastIndex)
            if (h.isValid) editor.markupModel.removeHighlighter(h)
        }

        // Build new sub-line text
        val subLineText = when (update) {
            is StatusUpdate.WaitingForModel -> "" // unreachable after early return above
            is StatusUpdate.ToolCalls -> {
                buildString {
                    update.calls.forEachIndexed { i, call ->
                        val treeChar = if (i == update.calls.lastIndex) "\u2514" else "\u251C"
                        append("$indent  $treeChar\u2500 ").append(formatToolCallDisplay(call)).append('\n')
                    }
                }
            }
        }

        // Replace everything after the first line's newline to the end of the range.
        // Invariant: insertStatusLines always appends '\n' after every line, so firstLineEnd + 1
        // points to the start of the sub-line region. The guard handles the degenerate case where
        // the range has been trimmed to a single line with no trailing newline.
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                UndoUtil.disableUndoIn(doc) {
                    if (display.range.isValid) {
                        val replaceStart = firstLineEnd + 1
                        val replaceEnd = display.range.endOffset
                        if (replaceStart <= replaceEnd) {
                            doc.replaceString(replaceStart, replaceEnd, subLineText)
                        }
                    }
                }
            }
        }

        // Add new highlighters for sub-lines, recalculating positions from the
        // (now-updated) first line end to account for any document shifts.
        if (subLineText.isNotEmpty() && display.range.isValid) {
            val updatedFirstLineEnd = doc.getLineEndOffset(doc.getLineNumber(display.range.startOffset))
            val (_, defaultFg) = statusLineColors(editor)
            val markup = editor.markupModel
            val subLines = subLineText.split('\n').dropLast(1)
            val subLineAttrs = TextAttributes().apply {
                foregroundColor = defaultFg
                fontType = Font.ITALIC
            }
            var pos = updatedFirstLineEnd + 1
            for (line in subLines) {
                if (!display.range.isValid) break
                val lineStart = pos
                val lineEndOffset = pos + line.length
                display.highlighters.add(
                    markup.addRangeHighlighter(
                        lineStart, lineEndOffset,
                        HighlighterLayer.LAST,
                        subLineAttrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                )
                pos = lineEndOffset + 1
            }
        }
    }

    private fun insertStatusLines(editor: Editor, offset: Int, prompt: String): Order89StatusDisplay? {
        val promptLines = formatPromptLines(prompt)
        val lineEnd = editor.document.getLineEndOffset(editor.document.getLineNumber(offset))
        val lineText = editor.document.getText(TextRange(offset, lineEnd))
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val statusLine1 = "$indent\u2726 Executing\u2026"
        val statusText = buildString {
            append(statusLine1).append('\n')
            promptLines.forEachIndexed { i, line ->
                val treeChar = if (i == promptLines.lastIndex) "\u2514" else "\u251C"
                append("$indent  $treeChar\u2500 ").append(line).append('\n')
            }
        }

        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                UndoUtil.disableUndoIn(editor.document) {
                    editor.document.insertString(offset, statusText)
                }
            }
        }

        // Range covers the full inserted text including trailing newline, so deletion removes all of it.
        val statusRange = editor.document.createRangeMarker(offset, offset + statusText.length)
        val symbolRange = editor.document.createRangeMarker(offset + indent.length, offset + indent.length + 1)

        val symbols = charArrayOf('\u2726', '\u2727', '\u2736', '\u2737', '\u2738', '\u2739')
        var symbolIndex = 0

        val (_, defaultFg) = statusLineColors(editor)
        val (shimmerBase, shimmerPeak) = shimmerColors(editor)

        val markup = editor.markupModel
        val highlighters = mutableListOf<RangeHighlighter>()
        val shimmerHighlighters = mutableListOf<RangeHighlighter>()
        val shimmerAttrs = mutableListOf<TextAttributes>()
        var pos = offset
        val lines = statusText.split('\n').dropLast(1) // drop trailing empty from final '\n'
        for ((index, line) in lines.withIndex()) {
            val lineStart = pos
            val lineEndOffset = pos + line.length
            if (index == 0) {
                // Per-character highlighters for shimmer effect on the first line
                for (charIdx in 0 until line.length) {
                    val charStart = lineStart + charIdx
                    val charEnd = charStart + 1
                    val attrs = TextAttributes().apply {
                        foregroundColor = shimmerBase
                        fontType = Font.ITALIC
                    }
                    shimmerAttrs.add(attrs)
                    shimmerHighlighters.add(
                        markup.addRangeHighlighter(
                            charStart, charEnd,
                            HighlighterLayer.LAST,
                            attrs,
                            HighlighterTargetArea.EXACT_RANGE
                        )
                    )
                }
            } else {
                val attrs = TextAttributes().apply {
                    foregroundColor = defaultFg
                    fontType = Font.ITALIC
                }
                highlighters.add(
                    markup.addRangeHighlighter(
                        lineStart, lineEndOffset,
                        HighlighterLayer.LAST,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                )
            }
            pos = lineEndOffset + 1 // +1 skips the newline
        }

        val firstLineLength = lines.firstOrNull()?.length ?: 0
        var shimmerPhase = 0f

        val shimmerTimer = Timer(100) {
            shimmerPhase = (shimmerPhase + 1f / 20f) % 1f
            for ((i, attrs) in shimmerAttrs.withIndex()) {
                val charPos = if (firstLineLength > 0) i.toFloat() / firstLineLength else 0f
                val dist = Math.min(
                    Math.abs(charPos - shimmerPhase),
                    Math.min(Math.abs(charPos - shimmerPhase + 1f), Math.abs(charPos - shimmerPhase - 1f))
                )
                val factor = if (dist < 4f / firstLineLength.coerceAtLeast(1)) {
                    ((Math.cos(dist * firstLineLength.coerceAtLeast(1) * Math.PI / 4.0) + 1.0) / 2.0).toFloat()
                } else {
                    0f
                }
                attrs.foregroundColor = interpolateColor(shimmerBase, shimmerPeak, factor)
            }
            if (!editor.isDisposed) editor.contentComponent.repaint()
        }
        shimmerTimer.start()

        val timer = Timer(300) {
            if (symbolRange.isValid) {
                symbolIndex = (symbolIndex + 1) % symbols.size
                ApplicationManager.getApplication().invokeLater({
                    ApplicationManager.getApplication().runWriteAction {
                        CommandProcessor.getInstance().runUndoTransparentAction {
                            UndoUtil.disableUndoIn(editor.document) {
                                if (symbolRange.isValid) {
                                    editor.document.replaceString(
                                        symbolRange.startOffset, symbolRange.endOffset,
                                        symbols[symbolIndex].toString()
                                    )
                                }
                            }
                        }
                    }
                }, ModalityState.defaultModalityState())
            }
            if (!editor.isDisposed) editor.contentComponent.repaint()
        }
        timer.start()

        return Order89StatusDisplay(statusRange, symbolRange, highlighters, shimmerHighlighters, timer, shimmerTimer)
    }
}

/** Cursor-aware ESC handler that cancels the Order 89 session matching the caret position. */
private class Order89EscAction(
    private val editor: Editor,
    private val parent: Order89Action
) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (editor.isDisposed) return
        val deque = editor.getUserData(Order89Action.SESSIONS_KEY) ?: return
        val doc = editor.document
        val caretLine = editor.caretModel.logicalPosition.line

        // Match if the caret is on the selection lines OR on the status display lines.
        val match = deque.firstOrNull { s ->
            if (!s.range.isValid) return@firstOrNull false
            val onSelection = caretLine >= doc.getLineNumber(s.range.startOffset) &&
                caretLine <= doc.getLineNumber(s.range.endOffset)
            val onStatus = s.statusDisplay?.range?.let { r ->
                r.isValid &&
                    caretLine >= doc.getLineNumber(r.startOffset) &&
                    caretLine <= doc.getLineNumber(r.endOffset)
            } ?: false
            onSelection || onStatus
        }

        if (match != null) {
            deque.remove(match)
            match.future.cancel(true)
            parent.removeStatusDisplay(editor, match.statusDisplay)
            match.range.dispose()
            if (deque.isEmpty()) {
                parent.unregisterEsc(editor)
            }
        } else {
            val originalEsc = ActionManager.getInstance()
                .getAction(IdeActions.ACTION_EDITOR_ESCAPE)
            originalEsc?.actionPerformed(e)
        }
    }
}
