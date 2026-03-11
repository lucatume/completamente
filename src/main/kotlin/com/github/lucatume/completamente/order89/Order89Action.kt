package com.github.lucatume.completamente.order89

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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import com.intellij.openapi.util.Disposer
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import javax.swing.KeyStroke
import javax.swing.Timer
import kotlin.math.sin

private val ORDER89_NEON_PINK = Color(255, 16, 240)
private val ORDER89_CYAN = Color(0, 255, 255)

private fun lerpColor(a: Color, b: Color, t: Double): Color {
    val ct = t.coerceIn(0.0, 1.0)
    return Color(
        (a.red + (b.red - a.red) * ct).toInt(),
        (a.green + (b.green - a.green) * ct).toInt(),
        (a.blue + (b.blue - a.blue) * ct).toInt()
    )
}

internal fun truncatePrompt(prompt: String, maxLength: Int = 60): String {
    val collapsed = prompt.replace('\n', ' ').replace('\r', ' ').trim()
    if (collapsed.length <= maxLength) return collapsed
    return collapsed.substring(0, maxLength) + "..."
}

data class Order89Session(
    val process: Process,
    val future: Future<Order89Result>,
    val inlay: Inlay<*>?,
    val range: RangeMarker
)

class Order89Action : AnAction() {

    companion object {
        private val SESSIONS_KEY = Key.create<ArrayDeque<Order89Session>>("order89.sessions")
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
        dialog.show()
        if (dialog.exitCode != com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE || dialog.promptText.isBlank()) return

        val order89Command = SettingsState.getInstance().order89Command
        if (order89Command.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("completamente")
                .createNotification("Order 89 command not configured", NotificationType.WARNING)
                .notify(project)
            return
        }

        val filePath = psiFile.virtualFile?.path ?: ""

        val request = Order89Request(
            commandTemplate = order89Command,
            prompt = dialog.promptText,
            filePath = filePath,
            fileContent = editor.document.text,
            language = psiFile.language.id,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            workingDirectory = project.basePath ?: "."
        )

        val caretOffset = editor.caretModel.offset
        val indentX = editor.offsetToXY(caretOffset).x
        val inlay = addExecutingInlay(editor, editor.document.getLineStartOffset(targetLine), indentX, dialog.promptText)

        val (process, future) = Order89Executor.execute(request)

        val rangeMarker = editor.document.createRangeMarker(selectionStart, selectionEnd)
        rangeMarker.isGreedyToRight = true
        val session = Order89Session(process, future, inlay, rangeMarker)
        val sessions = editor.getUserData(SESSIONS_KEY) ?: ArrayDeque<Order89Session>().also {
            editor.putUserData(SESSIONS_KEY, it)
        }
        val wasEmpty = sessions.isEmpty()
        sessions.push(session)

        if (wasEmpty) {
            val escAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    val deque = editor.getUserData(SESSIONS_KEY) ?: return
                    val doc = editor.document
                    val caretLine = editor.caretModel.logicalPosition.line

                    val match = deque.firstOrNull { s ->
                        s.range.isValid &&
                            caretLine >= doc.getLineNumber(s.range.startOffset) &&
                            caretLine <= doc.getLineNumber(s.range.endOffset)
                    }

                    if (match != null) {
                        deque.remove(match)
                        match.process.destroyForcibly()
                        match.future.cancel(true)
                        match.inlay?.dispose()
                        match.range.dispose()
                        if (deque.isEmpty()) {
                            unregisterEsc(editor)
                        }
                    } else {
                        val originalEsc = ActionManager.getInstance()
                            .getAction(IdeActions.ACTION_EDITOR_ESCAPE)
                        originalEsc?.actionPerformed(e)
                    }
                }
            }
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
                    if (result.success) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            // A disposed RangeMarker returns isValid=false, so ESC cancellation prevents stale writes here.
                            if (!session.range.isValid) return@runWriteCommandAction
                            editor.document.replaceString(session.range.startOffset, session.range.endOffset, result.output)
                            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                            val committedPsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                            if (committedPsiFile != null) {
                                CodeStyleManager.getInstance(project).reformatText(committedPsiFile, session.range.startOffset, session.range.endOffset)
                            }
                        }
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
                ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("completamente")
                        .createNotification(ex.message ?: "Unknown error", NotificationType.ERROR)
                        .notify(project)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    val deque = editor.getUserData(SESSIONS_KEY)
                    if (deque?.remove(session) == true) {
                        session.inlay?.dispose()
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

    private fun unregisterEsc(editor: Editor) {
        editor.getUserData(ESC_ACTION_KEY)?.unregisterCustomShortcutSet(editor.component)
        editor.putUserData(ESC_ACTION_KEY, null)
    }

    private fun addExecutingInlay(editor: Editor, offset: Int, indentX: Int, prompt: String): Inlay<*>? {
        val symbols = charArrayOf('\u2726', '\u2727', '\u2736', '\u2737', '\u2738', '\u2739')
        var symbolIndex = 0
        var frameCount = 0
        val truncated = truncatePrompt(prompt)

        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int = 0

            override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as? Graphics2D
                g2d?.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val lineHeight = editor.lineHeight
                g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

                val t = (sin(frameCount * 0.5) + 1.0) / 2.0
                val pulseColor = lerpColor(ORDER89_NEON_PINK, ORDER89_CYAN, t)

                val statusText = "${symbols[symbolIndex]} Executing Order 89"
                if (g2d != null) {
                    val fm = g2d.fontMetrics
                    val textWidth = fm.stringWidth(statusText)
                    if (textWidth > 0) {
                        val gradient = LinearGradientPaint(
                            indentX.toFloat(), 0f,
                            (indentX + textWidth).toFloat(), 0f,
                            floatArrayOf(0f, 1f),
                            arrayOf(pulseColor, lerpColor(ORDER89_CYAN, ORDER89_NEON_PINK, t))
                        )
                        g2d.paint = gradient
                    } else {
                        g2d.color = pulseColor
                    }
                    g2d.drawString(statusText, indentX, targetRegion.y + editor.ascent)
                } else {
                    g.color = pulseColor
                    g.drawString(statusText, indentX, targetRegion.y + editor.ascent)
                }

                val promptX = indentX + g.fontMetrics.stringWidth("${symbols[symbolIndex]} ")
                val promptColor = lerpColor(ORDER89_CYAN, ORDER89_NEON_PINK, t)
                if (g2d != null) {
                    val promptWidth = g2d.fontMetrics.stringWidth(truncated)
                    if (promptWidth > 0) {
                        g2d.paint = LinearGradientPaint(
                            promptX.toFloat(), 0f,
                            (promptX + promptWidth).toFloat(), 0f,
                            floatArrayOf(0f, 1f),
                            arrayOf(promptColor, lerpColor(ORDER89_NEON_PINK, ORDER89_CYAN, t))
                        )
                    } else {
                        g2d.color = promptColor
                    }
                    g2d.drawString(truncated, promptX, targetRegion.y + editor.ascent + lineHeight)
                } else {
                    g.color = promptColor
                    g.drawString(truncated, promptX, targetRegion.y + editor.ascent + lineHeight)
                }
            }

            override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight * 2
        }
        val inlay = editor.inlayModel.addBlockElement(offset, true, true, 0, renderer) ?: return null

        val timer = Timer(100) {
            frameCount++
            if (frameCount % 3 == 0) symbolIndex = (symbolIndex + 1) % symbols.size
            inlay.repaint()
        }
        timer.start()

        Disposer.register(inlay) { timer.stop() }

        return inlay
    }
}
