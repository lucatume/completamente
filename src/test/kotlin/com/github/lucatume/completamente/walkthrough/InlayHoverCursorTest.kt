package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.util.Disposer
import java.awt.Cursor
import java.awt.Rectangle
import java.awt.event.MouseEvent

/**
 * Tests for [installInlayHoverCursor]. Cursor visuals can't be unit-tested, but the
 * `editor.contentComponent.cursor` *value* set by the listener can — that's what AWT/Swing
 * reads when actually rendering the cursor. We dispatch synthetic [MouseEvent]s and assert
 * the cursor type after each.
 */
class InlayHoverCursorTest : BaseCompletionTest() {

    fun testCursorFlipsToHandWhenEnteringInlayAndBackOnExit() {
        val editor = myFixture.configureByText("Sample.kt", "first\nsecond\nthird\n").let {
            myFixture.editor.also { it.contentComponent.setSize(800, 600) }
        }
        val parent = Disposer.newDisposable("hover-test").also { Disposer.register(testRootDisposable, it) }

        // Stub inlay with a fixed bounds rectangle. We control bounds via a mutable holder so the
        // test owns the geometry — no dependency on actual paint/layout positioning.
        val inlayHolder = arrayOfNulls<Inlay<*>>(1)
        inlayHolder[0] = editor.inlayModel.addBlockElement(
            0, false, true, 0,
            object : com.intellij.openapi.editor.EditorCustomElementRenderer {
                override fun calcWidthInPixels(inlay: Inlay<*>) = 200
                override fun calcHeightInPixels(inlay: Inlay<*>) = 80
            },
        )!!.also { Disposer.register(parent, it) }

        val cc = editor.contentComponent
        cc.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)

        installInlayHoverCursor(editor, { inlayHolder[0] }, parent)

        val bounds: Rectangle = inlayHolder[0]!!.bounds
            ?: error("inlay bounds null — addBlockElement did not lay out")
        val inX = bounds.x + bounds.width / 2
        val inY = bounds.y + bounds.height / 2

        // Enter the inlay — cursor must flip to HAND.
        dispatchMouseMoved(cc, inX, inY)
        assertEquals(
            "cursor must be HAND_CURSOR while inside the inlay",
            Cursor.HAND_CURSOR,
            cc.cursor.type,
        )

        // Exit the inlay — cursor must revert to TEXT (editor-body default).
        val outX = bounds.x + bounds.width + 50
        val outY = bounds.y + bounds.height + 50
        dispatchMouseMoved(cc, outX, outY)
        assertEquals(
            "cursor must revert to TEXT_CURSOR on exit",
            Cursor.TEXT_CURSOR,
            cc.cursor.type,
        )
    }

    fun testListenerIsRemovedOnParentDispose() {
        val editor = myFixture.configureByText("Sample.kt", "x\n").let {
            myFixture.editor.also { it.contentComponent.setSize(800, 600) }
        }
        val parent = Disposer.newDisposable("hover-test-dispose").also { Disposer.register(testRootDisposable, it) }
        val cc = editor.contentComponent
        val before = cc.mouseMotionListeners.size
        installInlayHoverCursor(editor, { null }, parent)
        assertEquals("listener must be added", before + 1, cc.mouseMotionListeners.size)
        Disposer.dispose(parent)
        assertEquals("listener must be removed on dispose", before, cc.mouseMotionListeners.size)
    }

    fun testNullInlayKeepsCursorUnchanged() {
        val editor = myFixture.configureByText("Sample.kt", "x\n").let {
            myFixture.editor.also { it.contentComponent.setSize(800, 600) }
        }
        val parent = Disposer.newDisposable("hover-test-null").also { Disposer.register(testRootDisposable, it) }
        val cc = editor.contentComponent
        val sentinel = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        cc.cursor = sentinel

        installInlayHoverCursor(editor, { null }, parent)
        dispatchMouseMoved(cc, 10, 10)
        // Provider returned null → no inlay → outside transition can't fire from initial state
        // (lastInside starts false, current is false → no-op). Cursor stays as the sentinel.
        assertSame("cursor must remain unchanged when inlayProvider returns null", sentinel, cc.cursor)
    }

    private fun dispatchMouseMoved(component: java.awt.Component, x: Int, y: Int) {
        val evt = MouseEvent(component, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false)
        component.dispatchEvent(evt)
    }
}
