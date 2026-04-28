package com.github.lucatume.completamente.uitest.fim

import com.github.lucatume.completamente.uitest.BaseCompletamenteUiTest
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

// Draft: starts a clean editor on src/sample.kt, stages a fake llama infill
// response, and asserts ghost text via the InlineCompletion API. The exact
// API surface (InlineCompletion.getHandlerOrNull / InlineCompletionSession)
// is marked internal-experimental in IntelliJ 2024.3 — the JS below is the
// 0.x sketch; first run will likely surface a "no such method" or similar
// signal that an agent can resolve via the artefact recorder output.
@Ignore("WIP — InlineCompletion API surface needs validation against 2024.3.6")
class FimGhostTextUiTest : BaseCompletamenteUiTest() {

    @Test
    fun testGhostTextAppearsWhenInfillReturnsCompletion() {
        stageInfill(content = "completed_by_fim()")

        val ghostText = robot.callJs<String>(
            """
            var ProjectManager     = com.intellij.openapi.project.ProjectManager
            var FileEditorManager  = com.intellij.openapi.fileEditor.FileEditorManager
            var LocalFileSystem    = com.intellij.openapi.vfs.LocalFileSystem
            var InlineCompletion   = com.intellij.codeInsight.inline.completion.InlineCompletion
            var InlineCompletionEvent = com.intellij.codeInsight.inline.completion.InlineCompletionEvent
            var InlineCompletionSession = com.intellij.codeInsight.inline.completion.InlineCompletionSession

            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var baseDir = project.getBasePath()
            var file    = LocalFileSystem.getInstance().refreshAndFindFileByPath(baseDir + '/src/sample.kt')
            FileEditorManager.getInstance(project).openFile(file, true)
            var editor  = FileEditorManager.getInstance(project).getSelectedTextEditor()

            var handler = InlineCompletion.getHandlerOrNull(editor)
            handler.invokeEvent(new InlineCompletionEvent.DirectCall(editor, file, null))

            var deadline = java.lang.System.currentTimeMillis() + 5000
            var inlay = ''
            while (java.lang.System.currentTimeMillis() < deadline) {
                var session = InlineCompletionSession.getOrNull(editor)
                if (session !== null && session.getContext().getElements().size() > 0) {
                    inlay = String(session.getContext().getElements().get(0).getText())
                    break
                }
                java.lang.Thread.sleep(100)
            }
            inlay
            """.trimIndent(),
            runInEdt = false,
        )

        assertTrue(
            "ghost text should contain staged completion; got: '$ghostText'",
            ghostText.contains("completed_by_fim"),
        )
    }
}
