package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.services.AgentProcessSession
import com.github.lucatume.completamente.services.DebugLog
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WalkthroughAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val vf = psiFile?.virtualFile
        val enabled = if (project != null && editor != null && vf != null) {
            ReadAction.compute<Boolean, RuntimeException> {
                ProjectFileIndex.getInstance(project).isInContent(vf)
            }
        } else false
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val vf = psiFile.virtualFile ?: return

        val service = WalkthroughService.getInstance(project)
        // Trigger-again semantics: dispose any active session and cancel any in-flight task
        // before opening the dialog.
        service.cancelInFlight()
        service.setActive(null)

        // Re-check enablement (the file may have moved out of content between update() and
        // actionPerformed(); update() is best-effort, actionPerformed owns correctness).
        val inContent = ReadAction.compute<Boolean, RuntimeException> {
            ProjectFileIndex.getInstance(project).isInContent(vf)
        }
        if (!inContent) {
            notifyError(project, "Walkthrough is only available on files inside the project.")
            return
        }

        // EDT-side capture: dialog, snapshot, range conversion to 1-indexed wire format.
        val dialog = WalkthroughDialog(editor.component)
        if (!dialog.showAndWait() || dialog.promptText.isBlank()) return

        val request = captureRequest(editor, psiFile, dialog.promptText) ?: return

        val settings = SettingsState.getInstance().toSettings()
        val workingDir = project.basePath?.let { File(it) }
        val processSession = AgentProcessSession()
        val taskDisposable = Disposer.newDisposable("WalkthroughAction.task")
        Disposer.register(service, taskDisposable)
        val taskCancelled = AtomicBoolean(false)
        Disposer.register(taskDisposable, Disposable { taskCancelled.set(true); processSession.cancel() })
        service.setInFlight(taskDisposable)
        val runId = service.nextRunId()

        DebugLog.log("Walkthrough start: file=${request.filePath}, selection=${request.selectionStart}-${request.selectionEnd}")

        // Background: run the agent. A separate watcher polls for cancellation/project-close.
        val task = object : Task.Backgroundable(project, "Walkthrough: thinking…", /* canBeCancelled = */ true) {
            override fun run(indicator: ProgressIndicator) {
                spawnCancelWatcher(indicator, processSession, project, taskCancelled)
                val result = WalkthroughExecutor.execute(request, settings, workingDir, processSession)
                ApplicationManager.getApplication().invokeLater {
                    onResultEDT(project, editor, service, runId, result, request.prompt, request.filePath)
                }
            }
        }
        task.queue()
    }

    private fun spawnCancelWatcher(
        indicator: ProgressIndicator,
        processSession: AgentProcessSession,
        project: Project,
        taskCancelled: AtomicBoolean
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            while (true) {
                if (taskCancelled.get()) return@executeOnPooledThread
                if (indicator.isCanceled || project.isDisposed) {
                    processSession.cancel()
                    return@executeOnPooledThread
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@executeOnPooledThread
                }
            }
        }
    }

    private fun onResultEDT(
        project: Project,
        editor: Editor,
        service: WalkthroughService,
        runId: Long,
        result: WalkthroughResult,
        prompt: String,
        originFilePath: String
    ) {
        if (project.isDisposed) return
        // Race guard: if the user re-triggered while we were in flight, this run id is stale
        // and a newer task is already in motion. Drop the result without touching the
        // service's in-flight slot — the re-triggering action has already replaced it with
        // its own task disposable.
        if (runId != service.currentRunId()) return
        // Our run is the current one — clear the in-flight marker. setInFlight(null) calls
        // cancelInFlight() which disposes our taskDisposable (firing taskCancelled and
        // processSession.cancel()), but the process has already exited so this is a no-op.
        service.setInFlight(null)
        if (editor.isDisposed) return

        if (!result.success) {
            notifyError(project, result.errorMessage ?: "Walkthrough failed.")
            return
        }
        val walkthrough = result.walkthrough
        if (walkthrough == null) {
            notifyError(project, "Walkthrough produced no steps.")
            return
        }
        val session = WalkthroughSession.build(project, walkthrough, editor)
        if (session == null) {
            notifyError(project, "Every step in the walkthrough was unresolvable (file missing, outside project, or binary).")
            return
        }
        // Cache the freshly-built walkthrough before starting the session. Cache fingerprints
        // are captured here (under a read action inside `put`) so subsequent edits the user
        // makes during the live session don't pre-stale the entry.
        WalkthroughCache.getInstance(project).put(prompt, originFilePath, walkthrough)
        Disposer.register(service, session)
        service.setActive(session)
        session.start()
    }

    private fun captureRequest(editor: Editor, psiFile: com.intellij.psi.PsiFile, prompt: String): WalkthroughRequest? {
        val doc = editor.document
        val sel = editor.selectionModel
        val (selectionStart, selectionEnd, startLineIdx, startColIdx, endLineIdx, endColIdx) =
            if (sel.hasSelection()) {
                val s = sel.selectionStart
                val ePos = sel.selectionEnd
                val sLine = doc.getLineNumber(s)
                val eLine = doc.getLineNumber(ePos)
                CapturedRange(s, ePos,
                    sLine, s - doc.getLineStartOffset(sLine),
                    eLine, ePos - doc.getLineStartOffset(eLine))
            } else {
                // Caret-only: use the caret line, line-start to line-end. The wire range goes
                // L:1 to L:max(2, lineLength+1) (non-degenerate even on empty lines).
                val caretOffset = editor.caretModel.offset
                val caretLine = doc.getLineNumber(caretOffset)
                val lineStart = doc.getLineStartOffset(caretLine)
                val lineEnd = doc.getLineEndOffset(caretLine)
                val lineLength = lineEnd - lineStart
                val nonDegenerateEndCol = maxOf(1, lineLength)
                CapturedRange(lineStart, lineEnd, caretLine, 0, caretLine, nonDegenerateEndCol)
            }

        val filePath = psiFile.virtualFile?.path ?: ""
        return WalkthroughRequest(
            prompt = prompt,
            filePath = filePath,
            language = psiFile.language.id,
            fileContent = doc.text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            // Convert 0-indexed Document positions to 1-indexed wire format.
            startLine = startLineIdx + 1,
            startCol = startColIdx + 1,
            endLine = endLineIdx + 1,
            endCol = endColIdx + 1
        )
    }

    private data class CapturedRange(
        val start: Int, val end: Int,
        val startLine: Int, val startCol: Int,
        val endLine: Int, val endCol: Int
    )

    private fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("completamente")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
