package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.editor.colors.EditorColorsManager
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

    fun testDialogSizeIsCorrectFractionOfFallbackWidth() {
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
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border
        assertInstanceOf(border, CompoundBorder::class.java)
        val compoundBorder = border as CompoundBorder
        assertInstanceOf(compoundBorder.outsideBorder, TitledBorder::class.java)
        val titledBorder = compoundBorder.outsideBorder as TitledBorder
        assertEquals("Order 89", titledBorder.title)
        assertEquals(TitledBorder.CENTER, titledBorder.titleJustification)
        assertEquals(Font.PLAIN, titledBorder.titleFont.style)
        dialog.dispose()
    }

    fun testDialogTextAreaUsesEditorColors() {
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
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val textArea = findComponent(dialog.contentPane, JTextArea::class.java)
        assertNotNull(textArea)
        assertTrue("lineWrap should be true", textArea!!.lineWrap)
        assertTrue("wrapStyleWord should be true", textArea.wrapStyleWord)
        dialog.dispose()
    }

    fun testDarkenAndDesaturateProducesExpectedColor() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "darkenAndDesaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val neonPink = Color(255, 16, 240)
        val result = method.invoke(Order89Dialog.Companion, neonPink, 0.25) as Color

        assertEquals(191, result.red)
        assertEquals(57, result.green)
        assertEquals(183, result.blue)
    }

    fun testDarkenAndDesaturateWithZeroAmountPreservesColor() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "darkenAndDesaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val color = Color(255, 0, 0)
        val result = method.invoke(Order89Dialog.Companion, color, 0.0) as Color
        assertEquals(color.red, result.red)
        assertEquals(color.green, result.green)
        assertEquals(color.blue, result.blue)
    }

    fun testDarkenAndDesaturateWithFullAmountRemovesSaturationAndBrightness() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "darkenAndDesaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val color = Color(255, 0, 0)
        val result = method.invoke(Order89Dialog.Companion, color, 1.0) as Color
        val resultHsb = Color.RGBtoHSB(result.red, result.green, result.blue, null)
        assertEquals("Full amount should result in zero saturation", 0f, resultHsb[1], 0.01f)
        assertEquals("Full amount should result in zero brightness", 0f, resultHsb[2], 0.01f)
    }

    fun testDarkenAndDesaturateBlackStaysBlack() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "darkenAndDesaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val result = method.invoke(Order89Dialog.Companion, Color(0, 0, 0), 0.25) as Color
        assertEquals(0, result.red)
        assertEquals(0, result.green)
        assertEquals(0, result.blue)
    }

    fun testDarkenAndDesaturateWhiteDarkensEvenly() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "darkenAndDesaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val result = method.invoke(Order89Dialog.Companion, Color(255, 255, 255), 0.25) as Color
        assertEquals(191, result.red)
        assertEquals(191, result.green)
        assertEquals(191, result.blue)
    }

    fun testDarkenAndDesaturateGrayDarkensEvenly() {
        val method = Order89Dialog.Companion::class.java.getDeclaredMethod(
            "darkenAndDesaturate", Color::class.java, Double::class.java
        )
        method.isAccessible = true

        val result = method.invoke(Order89Dialog.Companion, Color(128, 128, 128), 0.25) as Color
        assertEquals(96, result.red)
        assertEquals(96, result.green)
        assertEquals(96, result.blue)
    }

    fun testBorderColorIsDarkenedNeonPink() {
        myFixture.configureByText("test.txt", "")
        val dialog = Order89Dialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border as CompoundBorder
        val titledBorder = border.outsideBorder as TitledBorder
        val borderColor = titledBorder.titleColor
        val neonPink = Color(255, 16, 240)
        val neonHsb = Color.RGBtoHSB(neonPink.red, neonPink.green, neonPink.blue, null)
        val borderHsb = Color.RGBtoHSB(borderColor.red, borderColor.green, borderColor.blue, null)
        assertTrue(
            "Border color saturation should be less than raw neon pink",
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
