package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest

class SettingsStateTest : BaseCompletionTest() {

    fun testToSettingsWithDefaults() {
        val state = SettingsState()
        val settings = state.toSettings()

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

    fun testOrder89CommandDefaultValue() {
        val state = SettingsState()
        val settings = state.toSettings()

        assertEquals("cat {{prompt_file}} | claude --dangerously-skip-permissions --print --output-format text", settings.order89Command)
    }

    fun testOrder89CommandCustomValueRoundTrips() {
        val state = SettingsState()
        state.order89Command = "custom-command --flag"

        val settings = state.toSettings()

        assertEquals("custom-command --flag", settings.order89Command)
    }

    fun testLoadStateCopiesOrder89Command() {
        val state = SettingsState()
        val source = SettingsState()
        source.order89Command = "another-command --test"

        state.loadState(source)

        assertEquals("another-command --test", state.order89Command)
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

    fun testToSettingsWithEmptyServerUrl() {
        val state = SettingsState()
        state.serverUrl = ""
        val settings = state.toSettings()
        assertEquals("", settings.serverUrl)
    }

    fun testToSettingsPreservesAllFields() {
        val state = SettingsState()
        state.serverUrl = "http://example.com:9999"
        state.ringNChunks = 4
        state.ringChunkSize = 32
        state.maxQueuedChunks = 2
        state.autoSuggestions = false
        state.maxRecentDiffs = 5
        state.serverCommand = "custom-server-cmd"
        state.order89Command = "echo test"

        val settings = state.toSettings()

        assertEquals("http://example.com:9999", settings.serverUrl)
        assertEquals(4, settings.ringNChunks)
        assertEquals(32, settings.ringChunkSize)
        assertEquals(2, settings.maxQueuedChunks)
        assertFalse(settings.autoSuggestions)
        assertEquals(5, settings.maxRecentDiffs)
        assertEquals("custom-server-cmd", settings.serverCommand)
        assertEquals("echo test", settings.order89Command)
    }

    fun testLoadStateCopiesAllFields() {
        val state = SettingsState()
        val source = SettingsState()
        source.serverUrl = "http://other:1234"
        source.ringNChunks = 99
        source.ringChunkSize = 200
        source.maxQueuedChunks = 50
        source.autoSuggestions = false
        source.maxRecentDiffs = 3
        source.serverCommand = "custom-server"
        source.order89Command = "custom-order89"

        state.loadState(source)

        assertEquals("http://other:1234", state.serverUrl)
        assertEquals(99, state.ringNChunks)
        assertEquals(200, state.ringChunkSize)
        assertEquals(50, state.maxQueuedChunks)
        assertFalse(state.autoSuggestions)
        assertEquals(3, state.maxRecentDiffs)
        assertEquals("custom-server", state.serverCommand)
        assertEquals("custom-order89", state.order89Command)
    }
}
