package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.BaseCompletionTest

class SettingsConfigurableTest : BaseCompletionTest() {

    fun testDisplayName() {
        val configurable = SettingsConfigurable()
        assertEquals("completamente", configurable.displayName)
    }

    fun testCreateComponentNotNull() {
        val configurable = SettingsConfigurable()
        val component = configurable.createComponent()
        assertNotNull(component)
    }

    fun testIsModifiedReturnsFalseInitially() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        assertFalse(configurable.isModified)
    }
}
