package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.SettingsState

class SettingsConfigurableTest : BaseCompletionTest() {

    override fun tearDown() {
        try {
            val defaults = SettingsState()
            val state = SettingsState.getInstance()
            state.serverUrl = defaults.serverUrl
            state.contextSize = defaults.contextSize
            state.nPredict = defaults.nPredict
            state.autoSuggestions = defaults.autoSuggestions
            state.ringNChunks = defaults.ringNChunks
            state.ringChunkSize = defaults.ringChunkSize
            state.maxQueuedChunks = defaults.maxQueuedChunks
            state.order89Command = defaults.order89Command
        } finally {
            super.tearDown()
        }
    }

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

    fun testApplyWithNonNumericContextSizeUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("contextSize")
        field.isAccessible = true
        field.set(configurable, "not_a_number")

        configurable.apply()

        assertEquals(32768, state.contextSize)
    }

    fun testApplyWithNonNumericNPredictUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("nPredict")
        field.isAccessible = true
        field.set(configurable, "xyz")

        configurable.apply()

        assertEquals(128, state.nPredict)
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

    fun testApplyWithValidContextSize() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("contextSize")
        field.isAccessible = true
        field.set(configurable, "4096")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals(4096, state.contextSize)
    }

    fun testApplyWithValidNPredict() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("nPredict")
        field.isAccessible = true
        field.set(configurable, "256")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals(256, state.nPredict)
    }

    fun testApplyWithAutoSuggestionsUnchecked() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("autoSuggestions")
        field.isAccessible = true
        field.set(configurable, false)

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertFalse(state.autoSuggestions)
    }

    fun testIsModifiedReturnsTrueAfterFieldChange() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CommandArea")
        field.isAccessible = true
        val area = field.get(configurable) as javax.swing.JTextArea
        area.text = "changed-command"

        assertTrue(configurable.isModified)
    }
}
