package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

data class ClipboardCopyEvent(
    val content: String,
    val project: Project
)

@Service(Service.Level.PROJECT)
class ClipboardCopyService(private val project: Project) {
    private val copyHandlers = mutableListOf<(ClipboardCopyEvent) -> Unit>()

    init {
        CopyPasteManager.getInstance().addContentChangedListener(
            object : CopyPasteManager.ContentChangedListener {
                override fun contentChanged(oldTransferable: Transferable?, newTransferable: Transferable?) {
                    val content = extractStringContent(newTransferable) ?: return
                    val event = ClipboardCopyEvent(content, project)
                    copyHandlers.forEach { it(event) }
                }
            },
            project
        )
    }

    fun onClipboardCopy(handler: (ClipboardCopyEvent) -> Unit) {
        copyHandlers.add(handler)
    }

    fun removeCopyHandler(handler: (ClipboardCopyEvent) -> Unit) {
        copyHandlers.remove(handler)
    }

}

private fun extractStringContent(transferable: Transferable?): String? {
    if (transferable == null) return null
    return try {
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
