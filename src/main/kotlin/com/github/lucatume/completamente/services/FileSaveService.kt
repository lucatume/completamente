package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

data class FileSaveEvent(
    val file: VirtualFile,
    val document: Document,
    val project: Project
)

data class BeforeFileSaveEvent(
    val document: Document,
    val project: Project
)

@Service(Service.Level.PROJECT)
class FileSaveService(private val project: Project) {
    private val saveHandlers = mutableListOf<(FileSaveEvent) -> Unit>()
    private val beforeSaveHandlers = mutableListOf<(BeforeFileSaveEvent) -> Unit>()

    init {
        project.messageBus.connect().subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val event = BeforeFileSaveEvent(document, project)
                    beforeSaveHandlers.forEach { it(event) }
                }

                override fun fileContentLoaded(file: VirtualFile, document: Document) {
                    // Not handling content loaded
                }

                override fun fileContentReloaded(file: VirtualFile, document: Document) {
                    // Not handling content reloaded
                }

                override fun beforeAllDocumentsSaving() {
                    // Not handling before all documents saving
                }

                override fun beforeFileContentReload(file: VirtualFile, document: Document) {
                    // Not handling before file content reload
                }

                override fun fileWithNoDocumentChanged(file: VirtualFile) {
                    // Not handling file with no document changed
                }

                override fun unsavedDocumentDropped(document: Document) {
                    // Not handling unsaved document dropped
                }
            }
        )
    }

    fun onFileSaved(handler: (FileSaveEvent) -> Unit) {
        saveHandlers.add(handler)
    }

    fun onBeforeFileSaved(handler: (BeforeFileSaveEvent) -> Unit) {
        beforeSaveHandlers.add(handler)
    }

    fun removeSaveHandler(handler: (FileSaveEvent) -> Unit) {
        saveHandlers.remove(handler)
    }

    fun removeBeforeSaveHandler(handler: (BeforeFileSaveEvent) -> Unit) {
        beforeSaveHandlers.remove(handler)
    }

    fun notifyFileSaved(file: VirtualFile, document: Document) {
        val event = FileSaveEvent(file, document, project)
        saveHandlers.forEach { it(event) }
    }

}
