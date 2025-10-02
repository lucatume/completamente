package com.github.lucatume.completamente.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CursorMovementTracker(private val project: Project) : Disposable {
    @Volatile
    private var lastMoveMs: Long = System.currentTimeMillis()

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            val editor = event.editor
            if (editor.project == project) {
                lastMoveMs = System.currentTimeMillis()
            }
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, this)
    }

    fun getLastMoveMs(): Long {
        return lastMoveMs
    }

    override fun dispose() {
        // Listener is automatically removed when this Disposable is disposed
    }
}
