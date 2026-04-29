package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest
import javax.swing.JComponent
import javax.swing.border.CompoundBorder
import javax.swing.border.TitledBorder

class WalkthroughDialogTest : BaseCompletionTest() {

    private fun headlessSkip(): Boolean = java.awt.GraphicsEnvironment.isHeadless()

    fun testPromptTextDefaultsToWalkthroughPrompt() {
        if (headlessSkip()) return
        myFixture.configureByText("test.txt", "")
        val dialog = WalkthroughDialog(myFixture.editor.component)
        assertEquals(WalkthroughDialog.DEFAULT_PROMPT, dialog.promptText)
        dialog.dispose()
    }

    fun testDefaultPromptMatchesSpec() {
        // The default prompt is part of the user-facing contract (spec section "Prompt
        // dialog"). Pin its exact wording so silent rewordings don't slip past review.
        assertEquals(
            "Walk me through this code: how it works, what other pieces of code calls it, etc.",
            WalkthroughDialog.DEFAULT_PROMPT
        )
    }

    fun testDialogIsUndecoratedAndModal() {
        if (headlessSkip()) return
        myFixture.configureByText("test.txt", "")
        val dialog = WalkthroughDialog(myFixture.editor.component)
        assertTrue("Dialog should be undecorated", dialog.isUndecorated)
        assertTrue("Dialog should be modal", dialog.isModal)
        dialog.dispose()
    }

    fun testDialogSizeIsCorrectFractionOfFallbackWidth() {
        if (headlessSkip()) return
        myFixture.configureByText("test.txt", "")
        val dialog = WalkthroughDialog(myFixture.editor.component)
        // In test, parentWindow is typically null, so fallback width is 640.
        // Width = 640 * 5 / 8 = 400, Height = 400 * 3 / 8 = 150.
        assertEquals(400, dialog.preferredSize.width)
        assertEquals(150, dialog.preferredSize.height)
        dialog.dispose()
    }

    fun testDialogTitledBorderUsesWalkthroughLabel() {
        if (headlessSkip()) return
        myFixture.configureByText("test.txt", "")
        val dialog = WalkthroughDialog(myFixture.editor.component)
        val border = (dialog.contentPane as JComponent).border
        assertInstanceOf(border, CompoundBorder::class.java)
        val outerCompound = (border as CompoundBorder).outsideBorder as CompoundBorder
        assertInstanceOf(outerCompound.insideBorder, TitledBorder::class.java)
        val titledBorder = outerCompound.insideBorder as TitledBorder
        assertEquals("[ Walkthrough ]", titledBorder.title)
        assertEquals(TitledBorder.CENTER, titledBorder.titleJustification)
        dialog.dispose()
    }

    fun testDialogResetsToDefaultPromptOnReconstruction() {
        // The spec is explicit: the default text is re-applied on every invocation — the
        // dialog does not remember prior prompts. Two sequential constructions both produce
        // the same default prompt.
        if (headlessSkip()) return
        myFixture.configureByText("test.txt", "")
        val first = WalkthroughDialog(myFixture.editor.component)
        assertEquals(WalkthroughDialog.DEFAULT_PROMPT, first.promptText)
        first.dispose()

        val second = WalkthroughDialog(myFixture.editor.component)
        assertEquals(WalkthroughDialog.DEFAULT_PROMPT, second.promptText)
        second.dispose()
    }
}
