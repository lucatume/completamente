package com.github.lucatume.completamente

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.scale.JBUIScale

abstract class BaseCompletionTest : BasePlatformTestCase() {
    override fun getTestDataPath() = "src/test/testData/completion"

    override fun setUp() {
        super.setUp()
        // Pin headless scaling to 1.0 so pixel-exact assertions in geometry tests
        // don't drift under different display scales.
        JBUIScale.setUserScaleFactor(1f)
    }
}
