package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest

class Order89DialogTest : BaseCompletionTest() {

    fun testPromptTextIsEmptyByDefault() {
        myFixture.configureByText("test.txt", "")
        val editor = myFixture.editor
        val dialog = Order89Dialog(editor.component)
        assertEquals("", dialog.promptText)
        dialog.close(0)
    }
}
