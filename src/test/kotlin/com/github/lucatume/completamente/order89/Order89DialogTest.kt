package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.border.CompoundBorder
import javax.swing.border.TitledBorder

class Order89DialogTest : BaseCompletionTest() {

    fun testPromptTextIsEmptyByDefault() {
        myFixture.configureByText("test.txt", "")
        val editor = myFixture.editor
        val dialog = Order89Dialog(editor.component)
        assertEquals("", dialog.promptText)
        dialog.dispose()
    }

    fun testDialogIsUndecoratedAndModal() {
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        assertTrue("Dialog should be undecorated", dialog.isUndecorated)
        assertTrue("Dialog should be modal", dialog.isModal)
        dialog.dispose()
    }

    fun testDialogSizeIsCorrectFractionOfParentWindow() {
        myFixture.configureByText("test.txt", "")
        val editor = myFixture.editor
        // The editor component's window may be null in test, so dialog falls back to 640.
        val dialog = Order89Dialog(editor.component)
        val parentWindow = javax.swing.SwingUtilities.getWindowAncestor(editor.component)
        val expectedWidth = (parentWindow?.width ?: 640) * 5 / 8
        val expectedHeight = expectedWidth * 3 / 8
        assertEquals("Width should be 5/8 of parent", expectedWidth, dialog.preferredSize.width)
        assertEquals("Height should be 3/8 of width", expectedHeight, dialog.preferredSize.height)
        dialog.dispose()
    }

    fun testDialogContainsTitledBorder() {
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border
        assertInstanceOf(border, CompoundBorder::class.java)
        val compoundBorder = border as CompoundBorder
        assertInstanceOf(compoundBorder.outsideBorder, TitledBorder::class.java)
        val titledBorder = compoundBorder.outsideBorder as TitledBorder
        assertEquals("Order 89", titledBorder.title)
        assertEquals(TitledBorder.CENTER, titledBorder.titleJustification)
        assertEquals(Font.BOLD, titledBorder.titleFont.style)
        dialog.dispose()
    }

    fun testDialogTextAreaHasSynthwaveColors() {
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val textArea = findComponent(dialog.contentPane, JTextArea::class.java)
        assertNotNull("Dialog should contain a JTextArea", textArea)
        assertEquals(Color(20, 10, 30), textArea!!.background)
        assertEquals(Color(0, 255, 255), textArea.foreground)
        assertEquals(Color(255, 16, 240), textArea.caretColor)
        assertTrue("Font should be monospaced", textArea.font.family.lowercase().contains("mono"))
        dialog.dispose()
    }

    fun testDialogTextAreaHasLineWrap() {
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val textArea = findComponent(dialog.contentPane, JTextArea::class.java)
        assertNotNull(textArea)
        assertTrue("lineWrap should be true", textArea!!.lineWrap)
        assertTrue("wrapStyleWord should be true", textArea.wrapStyleWord)
        dialog.dispose()
    }

    fun testDesaturateReducesSaturation() {
        // Use reflection to test the companion desaturate method.
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "desaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val neonPink = Color(255, 16, 240)
        val result = method.invoke(Order89Dialog.Companion, neonPink, 0.25) as Color

        // Verify the result has lower saturation than the original.
        val originalHsb = Color.RGBtoHSB(neonPink.red, neonPink.green, neonPink.blue, null)
        val resultHsb = Color.RGBtoHSB(result.red, result.green, result.blue, null)
        assertTrue(
            "Saturation should decrease: original=${originalHsb[1]}, result=${resultHsb[1]}",
            resultHsb[1] < originalHsb[1]
        )
        // Saturation should be exactly 75% of original (desaturated by 25%).
        assertEquals(originalHsb[1] * 0.75, resultHsb[1].toDouble(), 0.01)
    }

    fun testDesaturateWithZeroAmountPreservesColor() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "desaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val color = Color(255, 0, 0)
        val result = method.invoke(Order89Dialog.Companion, color, 0.0) as Color
        assertEquals(color.red, result.red)
        assertEquals(color.green, result.green)
        assertEquals(color.blue, result.blue)
    }

    fun testDesaturateWithFullAmountRemovesSaturation() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "desaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val color = Color(255, 0, 0)
        val result = method.invoke(Order89Dialog.Companion, color, 1.0) as Color
        val resultHsb = Color.RGBtoHSB(result.red, result.green, result.blue, null)
        assertEquals("Full desaturation should result in zero saturation", 0f, resultHsb[1], 0.01f)
    }

    fun testBorderColorIsDesaturatedNeonPink() {
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border as CompoundBorder
        val titledBorder = border.outsideBorder as TitledBorder
        // The title color is set to borderColor, which is desaturated neon pink.
        val borderColor = titledBorder.titleColor
        val neonPink = Color(255, 16, 240)
        val neonHsb = Color.RGBtoHSB(neonPink.red, neonPink.green, neonPink.blue, null)
        val borderHsb = Color.RGBtoHSB(borderColor.red, borderColor.green, borderColor.blue, null)
        assertTrue(
            "Border color saturation should be less than neon pink",
            borderHsb[1] < neonHsb[1]
        )
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
