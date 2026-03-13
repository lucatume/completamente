package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.impl.DocumentImpl

class RequestFimActionTest : BaseCompletionTest() {

    private fun createEvent(dataContext: DataContext): AnActionEvent {
        @Suppress("DEPRECATION")
        return AnActionEvent.createFromDataContext("TestPlace", Presentation(), dataContext)
    }

    private fun createEditorWithKind(kind: EditorKind): Editor {
        val doc = DocumentImpl("test content")
        return EditorFactory.getInstance().createEditor(doc, project, kind)
    }

    private fun dataContextOf(editor: Editor? = null, withProject: Boolean = true): DataContext {
        return DataContext { dataId ->
            when (dataId) {
                CommonDataKeys.EDITOR.name -> editor
                CommonDataKeys.PROJECT.name -> if (withProject) project else null
                else -> null
            }
        }
    }

    // -- update(): enabled only for MAIN_EDITOR with a project --

    fun testUpdateEnablesForMainEditor() {
        val editor = createEditorWithKind(EditorKind.MAIN_EDITOR)
        try {
            val event = createEvent(dataContextOf(editor))
            RequestFimAction().update(event)
            assertTrue("Should be enabled for MAIN_EDITOR with project", event.presentation.isEnabled)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testUpdateDisablesForConsoleEditor() {
        val editor = createEditorWithKind(EditorKind.CONSOLE)
        try {
            val event = createEvent(dataContextOf(editor))
            RequestFimAction().update(event)
            assertFalse("Should be disabled for CONSOLE editor", event.presentation.isEnabled)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testUpdateDisablesForPreviewEditor() {
        val editor = createEditorWithKind(EditorKind.PREVIEW)
        try {
            val event = createEvent(dataContextOf(editor))
            RequestFimAction().update(event)
            assertFalse("Should be disabled for PREVIEW editor", event.presentation.isEnabled)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testUpdateDisablesForUntypedEditor() {
        val doc = DocumentImpl("untyped content")
        val editor = EditorFactory.getInstance().createEditor(doc, project)
        try {
            val event = createEvent(dataContextOf(editor))
            RequestFimAction().update(event)
            assertFalse("Should be disabled for UNTYPED editor", event.presentation.isEnabled)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testUpdateDisablesWhenNoEditor() {
        val event = createEvent(dataContextOf(editor = null))
        RequestFimAction().update(event)
        assertFalse("Should be disabled when no editor", event.presentation.isEnabled)
    }

    fun testUpdateDisablesWhenNoProject() {
        val editor = createEditorWithKind(EditorKind.MAIN_EDITOR)
        try {
            val event = createEvent(dataContextOf(editor, withProject = false))
            RequestFimAction().update(event)
            assertFalse("Should be disabled when no project", event.presentation.isEnabled)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testUpdateDisablesWhenNeitherEditorNorProject() {
        val event = createEvent(dataContextOf(editor = null, withProject = false))
        RequestFimAction().update(event)
        assertFalse("Should be disabled when neither editor nor project", event.presentation.isEnabled)
    }

    // -- actionPerformed(): early-return guards --

    fun testActionPerformedWithConsoleEditorDoesNotThrow() {
        val editor = createEditorWithKind(EditorKind.CONSOLE)
        try {
            val event = createEvent(dataContextOf(editor))
            // EditorKind guard should early-return before reaching FimSuggestionManager.
            RequestFimAction().actionPerformed(event)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testActionPerformedWithPreviewEditorDoesNotThrow() {
        val editor = createEditorWithKind(EditorKind.PREVIEW)
        try {
            val event = createEvent(dataContextOf(editor))
            RequestFimAction().actionPerformed(event)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testActionPerformedWithNoEditorDoesNotThrow() {
        val event = createEvent(dataContextOf(editor = null))
        RequestFimAction().actionPerformed(event)
    }

    fun testActionPerformedWithNoProjectDoesNotThrow() {
        val editor = createEditorWithKind(EditorKind.MAIN_EDITOR)
        try {
            val event = createEvent(dataContextOf(editor, withProject = false))
            RequestFimAction().actionPerformed(event)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }
}
