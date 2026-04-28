package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.DEFAULT_ORDER89_CLI_COMMAND
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
            state.order89CliCommand = defaults.order89CliCommand
            state.debugLogging = defaults.debugLogging
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

    fun testApplyWithDebugLoggingEnabled() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("debugLogging")
        field.isAccessible = true
        field.set(configurable, true)

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertTrue(state.debugLogging)
    }

    fun testResetRestoresDebugLogging() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        state.debugLogging = false
        configurable.reset()

        val field = SettingsConfigurable::class.java.getDeclaredField("debugLogging")
        field.isAccessible = true
        assertFalse(field.get(configurable) as Boolean)
    }

    // -- Order 89 CLI command field --

    fun testOrder89CliCommandFieldDefaultsToBundledCommand() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CliCommand")
        field.isAccessible = true
        assertEquals(DEFAULT_ORDER89_CLI_COMMAND, field.get(configurable) as String)
    }

    fun testApplyWritesOrder89CliCommandToState() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CliCommand")
        field.isAccessible = true
        field.set(configurable, "claude --prompt-file %%prompt_file%%")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals("claude --prompt-file %%prompt_file%%", state.order89CliCommand)
    }

    fun testApplyBlankOrder89CliCommandFallsBackToDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CliCommand")
        field.isAccessible = true
        field.set(configurable, "   ")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals(DEFAULT_ORDER89_CLI_COMMAND, state.order89CliCommand)
    }
}
