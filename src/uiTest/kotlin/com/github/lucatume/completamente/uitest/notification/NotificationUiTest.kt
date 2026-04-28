package com.github.lucatume.completamente.uitest.notification

import com.github.lucatume.completamente.uitest.BaseCompletamenteUiTest
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

// Draft: stages a 500 response from fake llama, triggers a completion, and
// asserts an ERROR-type IDE notification surfaces. The plugin may or may not
// surface FIM errors as IDE notifications today — first run via the working
// harness will make the actual behaviour observable through the artefact
// recorder, and the assertion can then be tightened to match.
@Ignore("WIP — depends on FIM trigger path from FimGhostTextUiTest landing first")
class NotificationUiTest : BaseCompletamenteUiTest() {

    @Test
    fun testFimErrorSurfacesAsErrorNotification() {
        stageInfillError(status = 500, body = "{\"error\":\"backend down\"}")

        val sawErrorNotification = robot.callJs<Boolean>(
            """
            var ProjectManager       = com.intellij.openapi.project.ProjectManager
            var NotificationsManager = com.intellij.notification.NotificationsManager
            var Notification         = com.intellij.notification.Notification
            var FileEditorManager    = com.intellij.openapi.fileEditor.FileEditorManager
            var LocalFileSystem      = com.intellij.openapi.vfs.LocalFileSystem
            var InlineCompletion     = com.intellij.codeInsight.inline.completion.InlineCompletion
            var InlineCompletionEvent = com.intellij.codeInsight.inline.completion.InlineCompletionEvent

            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var baseDir = project.getBasePath()
            var file    = LocalFileSystem.getInstance().refreshAndFindFileByPath(baseDir + '/src/sample.kt')
            FileEditorManager.getInstance(project).openFile(file, true)
            var editor  = FileEditorManager.getInstance(project).getSelectedTextEditor()

            var handler = InlineCompletion.getHandlerOrNull(editor)
            if (handler !== null) {
                handler.invokeEvent(new InlineCompletionEvent.DirectCall(editor, file, null))
            }

            var deadline = java.lang.System.currentTimeMillis() + 5000
            while (java.lang.System.currentTimeMillis() < deadline) {
                var notes = NotificationsManager.getNotificationsManager()
                    .getNotificationsOfType(Notification.class, project)
                for (var i = 0; i < notes.length; i++) {
                    if (String(notes[i].getType()) === 'ERROR') return true
                }
                java.lang.Thread.sleep(200)
            }
            false
            """.trimIndent(),
            runInEdt = false,
        )

        assertTrue(
            "an ERROR-type notification should be surfaced when /infill returns 500",
            sawErrorNotification,
        )
    }
}
