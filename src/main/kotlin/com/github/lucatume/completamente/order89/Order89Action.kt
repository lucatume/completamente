package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.completion.collectReferencedFilePaths
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.KeyEvent
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CancellationException
import javax.swing.KeyStroke
import javax.swing.Timer

class Order89Action : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val selectedText = editor.selectionModel.selectedText ?: ""
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
        val referencedFiles = ReadAction.compute<List<String>, Throwable> {
            collectReferencedFilePaths(project, psiFile, filePath)
        }

        val request = Order89Request(
            commandTemplate = order89Command,
            prompt = dialog.promptText,
            selectedText = selectedText,
            filePath = filePath,
            fileContent = editor.document.text,
            language = psiFile.language.id,
            referencedFiles = referencedFiles,
            workingDirectory = project.basePath ?: "."
        )

        val caretOffset = editor.caretModel.offset
        val indentX = editor.offsetToXY(caretOffset).x
        val inlay = addExecutingInlay(editor, editor.document.getLineStartOffset(targetLine), indentX)

        val (process, future) = Order89Executor.execute(request)

        val escAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                process.destroyForcibly()
                future.cancel(true)
            }
        }
        escAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
            editor.component
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = future.get()
                ApplicationManager.getApplication().invokeLater {
                    if (result.success) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            editor.document.replaceString(selectionStart, selectionEnd, result.output)
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
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("completamente")
                        .createNotification(e.message ?: "Unknown error", NotificationType.ERROR)
                        .notify(project)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    inlay?.dispose()
                    escAction.unregisterCustomShortcutSet(editor.component)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    private fun addExecutingInlay(editor: Editor, offset: Int, indentX: Int): Inlay<*>? {
        val symbols = charArrayOf('\u2726', '\u2727', '\u2736', '\u2737', '\u2738', '\u2739')
        var symbolIndex = 0

        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int = 0

            override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
                g.color = JBColor.GRAY
                g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                g.drawString("${symbols[symbolIndex]} Executing Order 89", indentX, targetRegion.y + editor.ascent)
            }

            override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight
        }
        val inlay = editor.inlayModel.addBlockElement(offset, true, true, 0, renderer) ?: return null

        val timer = Timer(250) {
            symbolIndex = (symbolIndex + 1) % symbols.size
            inlay.repaint()
        }
        timer.start()

        Disposer.register(inlay) { timer.stop() }

        return inlay
    }
}
