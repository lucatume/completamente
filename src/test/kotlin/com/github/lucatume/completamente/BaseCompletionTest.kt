package com.github.lucatume.completamente

import com.intellij.testFramework.fixtures.BasePlatformTestCase

abstract class BaseCompletionTest : BasePlatformTestCase() {
    override fun getTestDataPath() = "src/test/testData/completion"
}
