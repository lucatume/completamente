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

    fun testResetRestoresStateValues() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()
        val originalUrl = state.serverUrl

        // Modify via the UI component to simulate a user change
        val urlFieldRef = SettingsConfigurable::class.java.getDeclaredField("serverUrlField")
        urlFieldRef.isAccessible = true
        val urlField = urlFieldRef.get(configurable) as javax.swing.JTextField
        urlField.text = "http://changed:9999"

        configurable.reset()

        assertEquals("URL field should be restored to state value", originalUrl, urlField.text)
    }

    fun testApplyPersistsChangedValues() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        // Modify via the serverUrlField (the actual UI component)
        val urlFieldRef = SettingsConfigurable::class.java.getDeclaredField("serverUrlField")
        urlFieldRef.isAccessible = true
        val urlField = urlFieldRef.get(configurable) as javax.swing.JTextField
        urlField.text = "http://new-server:1234"

        configurable.apply()

        assertEquals("State should reflect the new URL", "http://new-server:1234", state.serverUrl)
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
