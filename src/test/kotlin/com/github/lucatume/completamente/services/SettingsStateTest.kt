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

        assertEquals(Settings().order89Command, settings.order89Command)
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
}
