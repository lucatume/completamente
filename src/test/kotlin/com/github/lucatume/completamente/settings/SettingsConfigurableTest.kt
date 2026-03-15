package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.SettingsState

class SettingsConfigurableTest : BaseCompletionTest() {

    fun testDisplayName() {
        val configurable = SettingsConfigurable()
        assertEquals("completamente", configurable.displayName)
    }

    fun testIsModifiedReturnsFalseInitially() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        assertFalse(configurable.isModified)
    }

    fun testApplyWithNonNumericRingNChunksUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        // Set ringNChunks to a non-numeric value via reflection on the private field
        val field = SettingsConfigurable::class.java.getDeclaredField("ringNChunks")
        field.isAccessible = true
        field.set(configurable, "not_a_number")

        configurable.apply()

        assertEquals(16, state.ringNChunks)
    }

    fun testApplyWithNonNumericRingChunkSizeUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("ringChunkSize")
        field.isAccessible = true
        field.set(configurable, "abc")

        configurable.apply()

        assertEquals(64, state.ringChunkSize)
    }
}
