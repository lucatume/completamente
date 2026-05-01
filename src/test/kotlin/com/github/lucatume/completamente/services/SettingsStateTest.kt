package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest

class SettingsStateTest : BaseCompletionTest() {

    fun testToSettingsWithDefaults() {
        val state = SettingsState()
        val settings = state.toSettings()

        assertEquals("http://127.0.0.1:8012", settings.serverUrl)
        assertEquals(32768, settings.contextSize)
        assertEquals(128, settings.nPredict)
        assertTrue(settings.autoSuggestions)
        assertEquals(16, settings.ringNChunks)
        assertEquals(64, settings.ringChunkSize)
        assertEquals(16, settings.maxQueuedChunks)
    }

    fun testToSettingsWithCustomValues() {
        val state = SettingsState()
        state.ringNChunks = 32
        state.ringChunkSize = 128
        state.maxQueuedChunks = 8

        val settings = state.toSettings()

        assertEquals(32, settings.ringNChunks)
        assertEquals(128, settings.ringChunkSize)
        assertEquals(8, settings.maxQueuedChunks)
    }

    fun testGetStateReturnsThis() {
        val state = SettingsState()
        assertSame(state, state.state)
    }

    fun testLoadStateCopiesValues() {
        val state = SettingsState()
        val source = SettingsState()
        source.ringNChunks = 32
        source.ringChunkSize = 128

        state.loadState(source)

        assertEquals(32, state.ringNChunks)
        assertEquals(128, state.ringChunkSize)
    }

    fun testToSettingsWithZeroRingNChunks() {
        val state = SettingsState()
        state.ringNChunks = 0
        val settings = state.toSettings()
        assertEquals(0, settings.ringNChunks)
    }

    fun testToSettingsWithNegativeRingNChunks() {
        val state = SettingsState()
        state.ringNChunks = -1
        val settings = state.toSettings()
        assertEquals(-1, settings.ringNChunks)
    }

    fun testToSettingsWithZeroRingChunkSize() {
        val state = SettingsState()
        state.ringChunkSize = 0
        val settings = state.toSettings()
        assertEquals(0, settings.ringChunkSize)
    }

    fun testServerUrlDefaultValue() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertEquals("http://127.0.0.1:8012", settings.serverUrl)
    }

    fun testServerUrlCustomValueRoundTrips() {
        val state = SettingsState()
        state.serverUrl = "http://localhost:9999"
        val settings = state.toSettings()
        assertEquals("http://localhost:9999", settings.serverUrl)
    }

    fun testContextSizeDefaultValue() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertEquals(32768, settings.contextSize)
    }

    fun testContextSizeCustomValueRoundTrips() {
        val state = SettingsState()
        state.contextSize = 4096
        val settings = state.toSettings()
        assertEquals(4096, settings.contextSize)
    }

    fun testNPredictDefaultValue() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertEquals(128, settings.nPredict)
    }

    fun testNPredictCustomValueRoundTrips() {
        val state = SettingsState()
        state.nPredict = 256
        val settings = state.toSettings()
        assertEquals(256, settings.nPredict)
    }

    fun testAutoSuggestionsDefaultValue() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertTrue(settings.autoSuggestions)
    }

    fun testAutoSuggestionsCustomValueRoundTrips() {
        val state = SettingsState()
        state.autoSuggestions = false
        val settings = state.toSettings()
        assertFalse(settings.autoSuggestions)
    }

    fun testLoadStateCopiesFimFields() {
        val state = SettingsState()
        val source = SettingsState()
        source.serverUrl = "http://example.com:1234"
        source.contextSize = 8192
        source.nPredict = 64
        source.autoSuggestions = false

        state.loadState(source)

        assertEquals("http://example.com:1234", state.serverUrl)
        assertEquals(8192, state.contextSize)
        assertEquals(64, state.nPredict)
        assertFalse(state.autoSuggestions)
    }

    fun testToSettingsPreservesAllFields() {
        val state = SettingsState()
        state.serverUrl = "http://myserver:5000"
        state.contextSize = 16384
        state.nPredict = 64
        state.autoSuggestions = false
        state.ringNChunks = 4
        state.ringChunkSize = 32
        state.maxQueuedChunks = 2
        state.order89CliCommand = "agent @\"%%prompt_file%%\""
        state.debugLogging = true

        val settings = state.toSettings()

        assertEquals("http://myserver:5000", settings.serverUrl)
        assertEquals(16384, settings.contextSize)
        assertEquals(64, settings.nPredict)
        assertFalse(settings.autoSuggestions)
        assertEquals(4, settings.ringNChunks)
        assertEquals(32, settings.ringChunkSize)
        assertEquals(2, settings.maxQueuedChunks)
        assertEquals("agent @\"%%prompt_file%%\"", settings.order89CliCommand)
        assertTrue(settings.debugLogging)
    }

    fun testLoadStateCopiesAllFields() {
        val state = SettingsState()
        val source = SettingsState()
        source.serverUrl = "http://other:8080"
        source.contextSize = 2048
        source.nPredict = 32
        source.autoSuggestions = false
        source.ringNChunks = 99
        source.ringChunkSize = 200
        source.maxQueuedChunks = 50
        source.order89CliCommand = "custom @\"%%prompt_file%%\""
        source.debugLogging = true

        state.loadState(source)

        assertEquals("http://other:8080", state.serverUrl)
        assertEquals(2048, state.contextSize)
        assertEquals(32, state.nPredict)
        assertFalse(state.autoSuggestions)
        assertEquals(99, state.ringNChunks)
        assertEquals(200, state.ringChunkSize)
        assertEquals(50, state.maxQueuedChunks)
        assertEquals("custom @\"%%prompt_file%%\"", state.order89CliCommand)
        assertTrue(state.debugLogging)
    }

    // -- Order 89 CLI command --

    fun testOrder89CliCommandDefaultIsBundledPiInvocation() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertEquals(DEFAULT_ORDER89_CLI_COMMAND_PI, settings.order89CliCommand)
        assertTrue(
            "Default command must include the prompt-file placeholder",
            settings.order89CliCommand.contains("%%prompt_file%%")
        )
    }

    fun testOrder89CliCommandCustomValueRoundTrips() {
        val state = SettingsState()
        state.order89CliCommand = "claude --prompt-file %%prompt_file%%"
        val settings = state.toSettings()
        assertEquals("claude --prompt-file %%prompt_file%%", settings.order89CliCommand)
    }

    fun testLoadStateCopiesOrder89CliCommand() {
        val state = SettingsState()
        val source = SettingsState()
        source.order89CliCommand = "other-cli %%prompt_file%%"

        state.loadState(source)

        assertEquals("other-cli %%prompt_file%%", state.order89CliCommand)
    }

    // -- Debug logging --

    fun testDebugLoggingDefaultIsFalse() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertFalse(settings.debugLogging)
    }

    fun testDebugLoggingCustomValueRoundTrips() {
        val state = SettingsState()
        state.debugLogging = true
        val settings = state.toSettings()
        assertTrue(settings.debugLogging)
    }

    fun testLoadStateCopiesDebugLogging() {
        val state = SettingsState()
        val source = SettingsState()
        source.debugLogging = true

        state.loadState(source)

        assertTrue(state.debugLogging)
    }

    // -- pi / claude code template constants --

    fun testAllCliTemplatesContainPromptFilePlaceholder() {
        // Every template must contain the `%%prompt_file%%` placeholder so substitution works.
        for ((name, value) in listOf(
            "DEFAULT_ORDER89_CLI_COMMAND_PI" to DEFAULT_ORDER89_CLI_COMMAND_PI,
            "DEFAULT_ORDER89_CLI_COMMAND_CLAUDE" to DEFAULT_ORDER89_CLI_COMMAND_CLAUDE,
            "DEFAULT_WALKTHROUGH_CLI_COMMAND_PI" to DEFAULT_WALKTHROUGH_CLI_COMMAND_PI,
            "DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE" to DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE,
        )) {
            assertTrue("$name must contain %%prompt_file%%", value.contains("%%prompt_file%%"))
        }
    }

    fun testPiAndClaudeTemplatesAreDistinct() {
        // A copy-paste regression that left both templates the same would silently land users
        // on the wrong CLI when they click "Use claude code defaults". Lock the distinction.
        assertFalse(DEFAULT_ORDER89_CLI_COMMAND_PI == DEFAULT_ORDER89_CLI_COMMAND_CLAUDE)
        assertFalse(DEFAULT_WALKTHROUGH_CLI_COMMAND_PI == DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE)
    }

    fun testInitialWalkthroughDefaultIsPiTemplate() {
        // Order 89's default is already covered by `testOrder89CliCommandDefaultIsBundledPiInvocation`
        // above; cover the Walkthrough analog here so the shipping defaults for both groups are
        // pinned.
        assertEquals(DEFAULT_WALKTHROUGH_CLI_COMMAND_PI, SettingsState().walkthroughCliCommand)
    }
}
