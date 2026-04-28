package com.github.lucatume.completamente.uitest.order89

import com.github.lucatume.completamente.uitest.BaseCompletamenteUiTest
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

// Draft: invoking the Order 89 action via reflective JS wedged the EDT (likely
// because the action gathers a DataContext from a non-EDT-safe path). Two
// candidate fixes: (a) drive the action via a real keystroke through Robot
// instead of actionPerformed, (b) wait for DumbService.runWhenSmart before
// invoking. Re-enable once one is implemented and verified.
@Ignore("WIP — see Order 89 EDT wedge note in this file")
class Order89PopupUiTest : BaseCompletamenteUiTest() {

    @Test
    fun testOrder89AppliesCleanedFixtureOutput() {
        useFakeAgentFixture("rename-variable.txt")

        // Phase 1 (EDT): open file, set selection, invoke Order 89 action.
        robot.runJs(
            """
            var ProjectManager    = com.intellij.openapi.project.ProjectManager
            var ActionManager     = com.intellij.openapi.actionSystem.ActionManager
            var DataManager       = com.intellij.ide.DataManager
            var AnActionEvent     = com.intellij.openapi.actionSystem.AnActionEvent
            var FileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager
            var LocalFileSystem   = com.intellij.openapi.vfs.LocalFileSystem
            var WriteCommandAction = com.intellij.openapi.command.WriteCommandAction

            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var baseDir = project.getBasePath()
            var file    = LocalFileSystem.getInstance().refreshAndFindFileByPath(baseDir + '/src/sample.kt')
            FileEditorManager.getInstance(project).openFile(file, true)

            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
            WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({
                run: function() {
                    editor.getSelectionModel().setSelection(0, editor.getDocument().getTextLength())
                }
            }))

            var action  = ActionManager.getInstance().getAction('com.github.lucatume.completamente.order89.Order89Action')
            var context = DataManager.getInstance().getDataContext(editor.getComponent())
            var event   = AnActionEvent.createFromAnAction(action, null, '', context)
            action.actionPerformed(event)
            """.trimIndent(),
            runInEdt = true,
        )

        // Phase 2: poll the editor document for the fixture content.
        val deadline = System.currentTimeMillis() + 30_000
        var lastSeen = ""
        while (System.currentTimeMillis() < deadline) {
            lastSeen = robot.callJs<String>(
                """
                var ProjectManager    = com.intellij.openapi.project.ProjectManager
                var FileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager
                var ApplicationManager = com.intellij.openapi.application.ApplicationManager

                var project = ProjectManager.getInstance().getOpenProjects()[0]
                var editor  = FileEditorManager.getInstance(project).getSelectedTextEditor()
                var result  = ''
                ApplicationManager.getApplication().runReadAction(new java.lang.Runnable({
                    run: function() { result = String(editor.getDocument().getText()) }
                }))
                result
                """.trimIndent(),
                runInEdt = false,
            )
            if (lastSeen.contains("renamedVariable")) break
            Thread.sleep(500)
        }

        assertTrue(
            "Order 89 must replace selection with fixture content; got: $lastSeen",
            lastSeen.contains("renamedVariable"),
        )
    }
}
