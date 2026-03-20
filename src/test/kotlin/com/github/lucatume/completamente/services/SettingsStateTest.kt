package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.order89.ToolUsageMode

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

    fun testOrder89ServerUrlDefaultValue() {
        val state = SettingsState()
        val settings = state.toSettings()

        assertEquals("http://127.0.0.1:8017", settings.order89ServerUrl)
    }

    fun testOrder89ServerUrlCustomValueRoundTrips() {
        val state = SettingsState()
        state.order89ServerUrl = "http://localhost:9999"

        val settings = state.toSettings()

        assertEquals("http://localhost:9999", settings.order89ServerUrl)
    }

    fun testLoadStateCopiesOrder89ServerUrl() {
        val state = SettingsState()
        val source = SettingsState()
        source.order89ServerUrl = "http://other:1234"

        state.loadState(source)

        assertEquals("http://other:1234", state.order89ServerUrl)
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
        state.order89ServerUrl = "http://order89:8017"
        state.order89ToolUsage = "MANUAL"
        state.order89MaxToolRounds = 5

        val settings = state.toSettings()

        assertEquals("http://myserver:5000", settings.serverUrl)
        assertEquals(16384, settings.contextSize)
        assertEquals(64, settings.nPredict)
        assertFalse(settings.autoSuggestions)
        assertEquals(4, settings.ringNChunks)
        assertEquals(32, settings.ringChunkSize)
        assertEquals(2, settings.maxQueuedChunks)
        assertEquals("http://order89:8017", settings.order89ServerUrl)
        assertEquals(ToolUsageMode.MANUAL, settings.order89ToolUsage)
        assertEquals(5, settings.order89MaxToolRounds)
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
        source.order89ServerUrl = "http://custom:8017"
        source.order89ToolUsage = "AUTO"
        source.order89MaxToolRounds = 7

        state.loadState(source)

        assertEquals("http://other:8080", state.serverUrl)
        assertEquals(2048, state.contextSize)
        assertEquals(32, state.nPredict)
        assertFalse(state.autoSuggestions)
        assertEquals(99, state.ringNChunks)
        assertEquals(200, state.ringChunkSize)
        assertEquals(50, state.maxQueuedChunks)
        assertEquals("http://custom:8017", state.order89ServerUrl)
        assertEquals("AUTO", state.order89ToolUsage)
        assertEquals(7, state.order89MaxToolRounds)
    }

    // -- Tool usage settings tests --

    fun testToolUsageDefaultIsOff() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertEquals(ToolUsageMode.OFF, settings.order89ToolUsage)
    }

    fun testToolUsageManualRoundTrips() {
        val state = SettingsState()
        state.order89ToolUsage = "MANUAL"
        val settings = state.toSettings()
        assertEquals(ToolUsageMode.MANUAL, settings.order89ToolUsage)
    }

    fun testToolUsageAutoRoundTrips() {
        val state = SettingsState()
        state.order89ToolUsage = "AUTO"
        val settings = state.toSettings()
        assertEquals(ToolUsageMode.AUTO, settings.order89ToolUsage)
    }

    fun testToolUsageInvalidStringFallsBackToOff() {
        val state = SettingsState()
        state.order89ToolUsage = "INVALID_VALUE"
        val settings = state.toSettings()
        assertEquals(ToolUsageMode.OFF, settings.order89ToolUsage)
    }

    fun testMaxToolRoundsDefault() {
        val state = SettingsState()
        val settings = state.toSettings()
        assertEquals(3, settings.order89MaxToolRounds)
    }

    fun testMaxToolRoundsCustomValueRoundTrips() {
        val state = SettingsState()
        state.order89MaxToolRounds = 5
        val settings = state.toSettings()
        assertEquals(5, settings.order89MaxToolRounds)
    }

    fun testLoadStateCopiesToolFields() {
        val state = SettingsState()
        val source = SettingsState()
        source.order89ToolUsage = "MANUAL"
        source.order89MaxToolRounds = 10

        state.loadState(source)

        assertEquals("MANUAL", state.order89ToolUsage)
        assertEquals(10, state.order89MaxToolRounds)
    }
}
