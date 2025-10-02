package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

data class FileOpenEvent(
    val file: VirtualFile,
    val project: Project
)

data class FileCloseEvent(
    val file: VirtualFile,
    val project: Project
)

@Service(Service.Level.PROJECT)
class FileOpenCloseService(private val project: Project) {
    private val openHandlers = mutableListOf<(FileOpenEvent) -> Unit>()
    private val closeHandlers = mutableListOf<(FileCloseEvent) -> Unit>()

    init {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val event = FileOpenEvent(file, project)
                    openHandlers.forEach { it(event) }
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val event = FileCloseEvent(file, project)
                    closeHandlers.forEach { it(event) }
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    // Not handling selection changes
                }
            }
        )
    }

    fun onFileOpened(handler: (FileOpenEvent) -> Unit) {
        openHandlers.add(handler)
    }

    fun onFileClosed(handler: (FileCloseEvent) -> Unit) {
        closeHandlers.add(handler)
    }

    fun removeOpenHandler(handler: (FileOpenEvent) -> Unit) {
        openHandlers.remove(handler)
    }

    fun removeCloseHandler(handler: (FileCloseEvent) -> Unit) {
        closeHandlers.remove(handler)
    }

}
