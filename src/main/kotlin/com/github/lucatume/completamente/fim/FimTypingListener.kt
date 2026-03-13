package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.services.SettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

class FimTypingListener(private val project: Project) : DocumentListener, Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val debounceDelayMs = 300

    fun register() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(this, this)
    }

    override fun documentChanged(event: DocumentEvent) {
        if (!SettingsState.getInstance().autoSuggestions) return

        // Only trigger for documents backed by a file (skip terminals, consoles, search fields, etc.)
        FileDocumentManager.getInstance().getFile(event.document) ?: return

        // Don't trigger new suggestions while cycling through sub-edits
        val manager = project.service<FimSuggestionManager>()
        if (manager.isCyclingSubEdits) return

        val editor = findEditorForProject(event) ?: return

        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed && !editor.isDisposed) {
                manager.showSuggestion(editor)
            }
        }, debounceDelayMs)
    }

    private fun findEditorForProject(event: DocumentEvent): Editor? {
        val editors = EditorFactory.getInstance().getEditors(event.document, project)
        val mainEditor = editors.firstOrNull { it.editorKind == EditorKind.MAIN_EDITOR }
        if (mainEditor != null) return mainEditor

        // Fallback: check if the currently focused editor has this document
        val fileEditor = FileEditorManager.getInstance(project).selectedEditor
        val textEditor = fileEditor as? TextEditor ?: return null
        val editor = textEditor.editor
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return null
        return if (editor.document == event.document) editor else null
    }

    override fun dispose() {
        // Alarm is automatically disposed
    }
}
