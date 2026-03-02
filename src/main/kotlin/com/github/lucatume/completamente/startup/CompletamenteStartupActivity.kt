package com.github.lucatume.completamente.startup

import com.github.lucatume.completamente.fim.FimActionHandlerRegistrar
import com.github.lucatume.completamente.fim.FimTypingListener
import com.github.lucatume.completamente.services.ClipboardCopyService
import com.github.lucatume.completamente.services.FileOpenCloseService
import com.github.lucatume.completamente.services.FileSaveService
import com.github.lucatume.completamente.services.Settings
import com.github.lucatume.completamente.services.SettingsState
import com.github.lucatume.completamente.services.ChunksRingBuffer
import com.github.lucatume.completamente.services.DiffTracker
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.github.lucatume.completamente.completion.pickChunkFromFile
import com.github.lucatume.completamente.completion.pickChunkFromText
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor

class CompletamenteStartupActivity : ProjectActivity {
    private fun handleFile(
        file: VirtualFile,
        project: Project,
        settings: Settings,
        setCursorLine: Int? = null
    ) {
        val cursorLine: Int? = setCursorLine ?: FileEditorManager.getInstance(project)
            .getSelectedEditor(file)
            ?.let { it as? TextEditor }
            ?.editor
            ?.caretModel
            ?.logicalPosition
            ?.line
        val isModified = FileDocumentManager.getInstance().isFileModified(file)
        val chunksRingBuffer = project.service<ChunksRingBuffer>()

        pickChunkFromFile(
            file,
            cursorLine,
            isModified,
            settings,
            chunksRingBuffer.getRingChunks(),
            chunksRingBuffer.getRingQueued()
        )
    }

    override suspend fun execute(project: Project) {
        val settings = SettingsState.getInstance().toSettings()

        val diffTracker = project.service<DiffTracker>()

        project.service<FileOpenCloseService>().onFileOpened { event ->
            // Snapshot the original content on first open
            ReadAction.run<RuntimeException> {
                val doc = FileDocumentManager.getInstance().getDocument(event.file)
                if (doc != null) {
                    diffTracker.snapshotOriginalContent(event.file.path, doc.text)
                }
            }
            // When opening a file, set the cursor line at `null` to pick from its center.
            // The IDE would otherwise say the cursor is at the start of the file.
            handleFile(event.file, project, settings, null)
        }

        project.service<FileOpenCloseService>().onFileClosed { event ->
            handleFile(event.file, project, settings)
        }

        project.service<FileSaveService>().onBeforeFileSaved { event ->
            val file = FileDocumentManager.getInstance().getFile(event.document) ?: return@onBeforeFileSaved
            handleFile(file, project, settings)
        }

        project.service<ClipboardCopyService>().onClipboardCopy { event ->
            val filename = event.project.projectFile?.name ?: "clipboard"
            val chunksRingBuffer = project.service<ChunksRingBuffer>()
            pickChunkFromText(
                event.content,
                filename,
                settings,
                chunksRingBuffer.getRingChunks(),
                chunksRingBuffer.getRingQueued()
            )
        }

        // Snapshot all currently-open files at startup
        ReadAction.run<RuntimeException> {
            for (editor in FileEditorManager.getInstance(project).allEditors) {
                val textEditor = editor as? TextEditor ?: continue
                val file = textEditor.file ?: continue
                val doc = FileDocumentManager.getInstance().getDocument(file) ?: continue
                diffTracker.snapshotOriginalContent(file.path, doc.text)
            }
        }

        // Register DiffTracker to listen for document changes
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(diffTracker, diffTracker)

        // Finalize pending edits when files are closed
        project.service<FileOpenCloseService>().onFileClosed { event ->
            diffTracker.finalizePendingEdit(event.file.path)
        }

        // Register FIM action handlers (Tab/Escape)
        FimActionHandlerRegistrar.register()

        // Start FIM typing listener for auto-suggestions
        val typingListener = FimTypingListener(project)
        typingListener.register()
    }

}
