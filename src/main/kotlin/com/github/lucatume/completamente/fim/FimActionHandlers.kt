package com.github.lucatume.completamente.fim

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

class FimTabHandler(private val originalHandler: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val project = editor.project
        if (project != null) {
            val manager = project.service<FimSuggestionManager>()
            if (manager.hasSuggestion && manager.currentEditor == editor) {
                manager.acceptSuggestion()
                return
            }
        }
        originalHandler.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
        val project = editor.project
        if (project != null) {
            val manager = project.service<FimSuggestionManager>()
            if (manager.hasSuggestion && manager.currentEditor == editor) {
                return true
            }
        }
        return originalHandler.isEnabled(editor, caret, dataContext)
    }
}

class FimEscapeHandler(private val originalHandler: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val project = editor.project
        if (project != null) {
            val manager = project.service<FimSuggestionManager>()
            if (manager.hasSuggestion && manager.currentEditor == editor) {
                manager.dismissSuggestion()
                return
            }
        }
        originalHandler.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
        val project = editor.project
        if (project != null) {
            val manager = project.service<FimSuggestionManager>()
            if (manager.hasSuggestion && manager.currentEditor == editor) {
                return true
            }
        }
        return originalHandler.isEnabled(editor, caret, dataContext)
    }
}
