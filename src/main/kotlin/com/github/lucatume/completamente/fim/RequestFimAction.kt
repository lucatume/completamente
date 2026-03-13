package com.github.lucatume.completamente.fim

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind

class RequestFimAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val project = e.project ?: return
        project.service<FimSuggestionManager>().showSuggestion(editor)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor != null
                && editor.editorKind == EditorKind.MAIN_EDITOR
                && e.project != null
    }
}
