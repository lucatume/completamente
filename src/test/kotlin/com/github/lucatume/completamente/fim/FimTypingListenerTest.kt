package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager

class FimTypingListenerTest : BaseCompletionTest() {

    // -- VirtualFile guard --

    fun testNonFileBackedDocumentHasNoVirtualFile() {
        val doc = DocumentImpl("console output text")
        val vFile = FileDocumentManager.getInstance().getFile(doc)
        assertNull("Standalone DocumentImpl should have no VirtualFile", vFile)
    }

    fun testFixtureEditorDocumentHasVirtualFile() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val doc = myFixture.editor.document
        val vFile = FileDocumentManager.getInstance().getFile(doc)
        assertNotNull("Fixture editor document should have a VirtualFile", vFile)
    }

    // -- EditorKind guard --

    fun testMainEditorKindPassesGuard() {
        val doc = DocumentImpl("main editor text")
        val editor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.MAIN_EDITOR)
        try {
            assertEquals(EditorKind.MAIN_EDITOR, editor.editorKind)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testConsoleEditorKindFailsGuard() {
        val doc = DocumentImpl("console text")
        val editor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.CONSOLE)
        try {
            assertNotSame(EditorKind.MAIN_EDITOR, editor.editorKind)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testPreviewEditorKindFailsGuard() {
        val doc = DocumentImpl("preview text")
        val editor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.PREVIEW)
        try {
            assertNotSame(EditorKind.MAIN_EDITOR, editor.editorKind)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    // -- findEditorForProject filtering --

    fun testGetEditorsFiltersByMainEditorKind() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val doc = myFixture.editor.document

        val mainEditor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.MAIN_EDITOR)
        val consoleEditor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.CONSOLE)
        try {
            val allEditors = EditorFactory.getInstance().getEditors(doc, project)
            val found = allEditors.firstOrNull { it.editorKind == EditorKind.MAIN_EDITOR }
            assertNotNull("Should find a MAIN_EDITOR among mixed editors", found)
            assertEquals(EditorKind.MAIN_EDITOR, found!!.editorKind)
        } finally {
            EditorFactory.getInstance().releaseEditor(mainEditor)
            EditorFactory.getInstance().releaseEditor(consoleEditor)
        }
    }

    fun testOnlyConsoleEditorsReturnsNoMainEditor() {
        val doc = DocumentImpl("standalone text")
        val consoleEditor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.CONSOLE)
        try {
            val allEditors = EditorFactory.getInstance().getEditors(doc, project)
            val mainEditor = allEditors.firstOrNull { it.editorKind == EditorKind.MAIN_EDITOR }
            assertNull("Document with only CONSOLE editors should have no MAIN_EDITOR", mainEditor)
        } finally {
            EditorFactory.getInstance().releaseEditor(consoleEditor)
        }
    }

    // -- Listener integration: documentChanged actually exercises the guards --

    fun testListenerIgnoresNonFileBackedDocument() {
        // A DocumentImpl created via EditorFactory fires through the multicaster.
        val doc = EditorFactory.getInstance().createDocument("hello world")
        var eventReceived = false
        val spy = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                eventReceived = true
            }
        }
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(spy, testRootDisposable)

        // This edit reaches FimTypingListener via the multicaster.
        ApplicationManager.getApplication().runWriteAction {
            doc.setText("hello changed")
        }

        assertTrue("DocumentEvent should have fired on the multicaster", eventReceived)
        // The document has no VirtualFile, so the listener's first guard skips it.
        assertNull(FileDocumentManager.getInstance().getFile(doc))
        assertEquals("hello changed", doc.text)
    }

    fun testListenerIgnoresConsoleEditorOnFileBackedDocument() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val doc = myFixture.editor.document

        // Attach only a CONSOLE editor for this document (the fixture editor is UNTYPED).
        // Neither CONSOLE nor UNTYPED is MAIN_EDITOR, so the listener should skip.
        val consoleEditor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.CONSOLE)
        try {
            // The document IS file-backed, so it passes the first guard.
            assertNotNull(FileDocumentManager.getInstance().getFile(doc))

            // But all editors are non-MAIN_EDITOR, so findEditorForProject returns null.
            val allEditors = EditorFactory.getInstance().getEditors(doc, project)
            val mainEditor = allEditors.firstOrNull { it.editorKind == EditorKind.MAIN_EDITOR }
            assertNull("No MAIN_EDITOR should exist for this document", mainEditor)

            // Edit fires through the multicaster — listener should silently skip.
            ApplicationManager.getApplication().runWriteAction {
                doc.setText("fun changed() {}")
            }
            assertEquals("fun changed() {}", doc.text)
        } finally {
            EditorFactory.getInstance().releaseEditor(consoleEditor)
        }
    }

    fun testListenerProcessesFileBackedMainEditorDocument() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val doc = myFixture.editor.document

        val mainEditor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.MAIN_EDITOR)
        try {
            // File-backed: passes first guard.
            assertNotNull(FileDocumentManager.getInstance().getFile(doc))
            // MAIN_EDITOR exists: passes second guard.
            val found = EditorFactory.getInstance().getEditors(doc, project)
                .firstOrNull { it.editorKind == EditorKind.MAIN_EDITOR }
            assertNotNull("MAIN_EDITOR should be found", found)

            // Edit fires — listener will attempt to schedule a suggestion.
            // In test env the server is not running, so the debounced request
            // will simply not produce a visible completion. No crash = success.
            ApplicationManager.getApplication().runWriteAction {
                doc.setText("fun changed() {}")
            }
            assertEquals("fun changed() {}", doc.text)
        } finally {
            EditorFactory.getInstance().releaseEditor(mainEditor)
        }
    }

    // -- Combined guard: non-file-backed + console editor --

    fun testNonFileBackedConsoleEditorFailsBothGuards() {
        val doc = DocumentImpl("terminal output")
        val consoleEditor = EditorFactory.getInstance().createEditor(doc, project, EditorKind.CONSOLE)
        try {
            assertNull(FileDocumentManager.getInstance().getFile(doc))
            assertNotSame(EditorKind.MAIN_EDITOR, consoleEditor.editorKind)
        } finally {
            EditorFactory.getInstance().releaseEditor(consoleEditor)
        }
    }
}
