package com.github.lucatume.completamente.fim

import com.intellij.openapi.editor.actionSystem.EditorActionManager

object FimActionHandlerRegistrar {
    private var registered = false

    fun register() {
        if (registered) return
        registered = true

        val actionManager = EditorActionManager.getInstance()

        // Wrap the Tab handler
        val originalTabHandler = actionManager.getActionHandler("EditorTab")
        actionManager.setActionHandler("EditorTab", FimTabHandler(originalTabHandler))

        // Wrap the Escape handler
        val originalEscapeHandler = actionManager.getActionHandler("EditorEscape")
        actionManager.setActionHandler("EditorEscape", FimEscapeHandler(originalEscapeHandler))
    }
}
