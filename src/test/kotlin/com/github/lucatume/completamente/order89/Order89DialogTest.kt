package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.border.CompoundBorder
import javax.swing.border.TitledBorder

class Order89DialogTest : BaseCompletionTest() {

    fun testPromptTextIsEmptyByDefault() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val editor = myFixture.editor
        val dialog = Order89Dialog(editor.component)
        assertEquals("", dialog.promptText)
        dialog.dispose()
    }

    fun testDialogIsUndecoratedAndModal() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        assertTrue("Dialog should be undecorated", dialog.isUndecorated)
        assertTrue("Dialog should be modal", dialog.isModal)
        dialog.dispose()
    }

    fun testDialogSizeIsCorrectFractionOfFallbackWidth() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val editor = myFixture.editor
        val dialog = Order89Dialog(editor.component)
        // In test, parentWindow is typically null, so fallback width is 640.
        // Width = 640 * 5 / 8 = 400, Height = 400 * 3 / 8 = 150.
        assertEquals("Width should be 400 (5/8 of 640 fallback)", 400, dialog.preferredSize.width)
        assertEquals("Height should be 150 (3/8 of 400)", 150, dialog.preferredSize.height)
        dialog.dispose()
    }

    fun testDialogContainsTitledBorder() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border
        assertInstanceOf(border, CompoundBorder::class.java)
        // outer compound: outer empty + titled
        val outerCompound = (border as CompoundBorder).outsideBorder as CompoundBorder
        assertInstanceOf(outerCompound.insideBorder, TitledBorder::class.java)
        val titledBorder = outerCompound.insideBorder as TitledBorder
        assertEquals("[ Order 89 ]", titledBorder.title)
        assertEquals(TitledBorder.CENTER, titledBorder.titleJustification)
        assertEquals(Font.PLAIN, titledBorder.titleFont.style)
        dialog.dispose()
    }

    fun testDialogTextAreaUsesEditorColors() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val textArea = findComponent(dialog.contentPane, JTextArea::class.java)
        assertNotNull("Dialog should contain a JTextArea", textArea)
        val editorScheme = EditorColorsManager.getInstance().globalScheme
        assertEquals(editorScheme.defaultBackground, textArea!!.background)
        assertEquals(editorScheme.defaultForeground, textArea.foreground)
        assertEquals(editorScheme.defaultForeground, textArea.caretColor)
        assertTrue("Font should be monospaced", textArea.font.family.lowercase().contains("mono"))
        dialog.dispose()
    }

    fun testDialogTextAreaHasLineWrap() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val textArea = findComponent(dialog.contentPane, JTextArea::class.java)
        assertNotNull(textArea)
        assertTrue("lineWrap should be true", textArea!!.lineWrap)
        assertTrue("wrapStyleWord should be true", textArea.wrapStyleWord)
        dialog.dispose()
    }

    fun testBorderColorMatchesEditorDefaultForeground() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border as CompoundBorder
        val outerCompound = border.outsideBorder as CompoundBorder
        val titledBorder = outerCompound.insideBorder as TitledBorder
        val editorScheme = EditorColorsManager.getInstance().globalScheme
        assertEquals(editorScheme.defaultForeground, titledBorder.titleColor)
        dialog.dispose()
    }

    /** Recursively finds the first component of the given type in a container. */
    private fun <T> findComponent(container: java.awt.Container, clazz: Class<T>): T? {
        for (component in container.components) {
            if (clazz.isInstance(component)) {
                @Suppress("UNCHECKED_CAST")
                return component as T
            }
            if (component is java.awt.Container) {
                val found = findComponent(component, clazz)
                if (found != null) return found
            }
        }
        return null
    }
}
