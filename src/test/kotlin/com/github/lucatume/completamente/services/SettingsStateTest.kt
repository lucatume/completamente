package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest

class SettingsStateTest : BaseCompletionTest() {

    fun testToSettingsWithDefaults() {
        val state = SettingsState()
        val settings = state.toSettings()

        assertEquals("http://127.0.0.1:8012/infill", settings.endpoint)
        assertEquals("", settings.apiKey)
        assertEquals("", settings.model)
        assertEquals(256, settings.nPrefix)
        assertEquals(64, settings.nSuffix)
        assertEquals(4, settings.tabstop)
        assertEquals(128, settings.nPredict)
        assertEquals(emptyList<String>(), settings.stopStrings)
        assertEquals(500, settings.tMaxPromptMs)
        assertEquals(1000, settings.tMaxPredictMs)
        assertEquals(250, settings.maxCacheKeys)
        assertEquals(1000L, settings.ringUpdateMs)
        assertEquals(64, settings.ringChunkSize)
        assertEquals(1024, settings.ringScope)
        assertEquals(16, settings.ringNChunks)
        assertEquals(16, settings.maxQueuedChunks)
        assertEquals(8, settings.maxLineSuffix)
    }

    fun testToSettingsWithCustomValues() {
        val state = SettingsState()
        state.endpoint = "http://localhost:8080/infill"
        state.apiKey = "test-key"
        state.model = "test-model"
        state.nPrefix = 128
        state.nSuffix = 32
        state.stopStrings = "stop1, stop2, stop3"

        val settings = state.toSettings()

        assertEquals("http://localhost:8080/infill", settings.endpoint)
        assertEquals("test-key", settings.apiKey)
        assertEquals("test-model", settings.model)
        assertEquals(128, settings.nPrefix)
        assertEquals(32, settings.nSuffix)
        assertEquals(listOf("stop1", "stop2", "stop3"), settings.stopStrings)
    }

    fun testToSettingsWithEmptyStopStrings() {
        val state = SettingsState()
        state.stopStrings = ""

        val settings = state.toSettings()
        assertEquals(emptyList<String>(), settings.stopStrings)
    }

    fun testToSettingsWithWhitespaceOnlyStopStrings() {
        val state = SettingsState()
        state.stopStrings = "   "

        val settings = state.toSettings()
        assertEquals(emptyList<String>(), settings.stopStrings)
    }

    fun testGetStateReturnsThis() {
        val state = SettingsState()
        assertSame(state, state.state)
    }

    fun testLoadStateCopiesValues() {
        val state = SettingsState()
        val source = SettingsState()
        source.endpoint = "http://custom:9999/test"
        source.nPrefix = 512

        state.loadState(source)

        assertEquals("http://custom:9999/test", state.endpoint)
        assertEquals(512, state.nPrefix)
    }

    fun testToSettingsPreservesAllNumericValues() {
        val state = SettingsState()
        state.nPrefix = 100
        state.nSuffix = 50
        state.nPredict = 200
        state.maxLineSuffix = 12
        state.tMaxPromptMs = 750
        state.tMaxPredictMs = 1500
        state.ringNChunks = 32
        state.ringChunkSize = 128
        state.ringScope = 2048
        state.ringUpdateMs = 500L
        state.maxQueuedChunks = 8
        state.maxCacheKeys = 100
        state.tabstop = 2

        val settings = state.toSettings()

        assertEquals(100, settings.nPrefix)
        assertEquals(50, settings.nSuffix)
        assertEquals(200, settings.nPredict)
        assertEquals(12, settings.maxLineSuffix)
        assertEquals(750, settings.tMaxPromptMs)
        assertEquals(1500, settings.tMaxPredictMs)
        assertEquals(32, settings.ringNChunks)
        assertEquals(128, settings.ringChunkSize)
        assertEquals(2048, settings.ringScope)
        assertEquals(500L, settings.ringUpdateMs)
        assertEquals(8, settings.maxQueuedChunks)
        assertEquals(100, settings.maxCacheKeys)
        assertEquals(2, settings.tabstop)
    }

    fun testStopStringsTrimsWhitespace() {
        val state = SettingsState()
        state.stopStrings = "  stop1  ,  stop2  ,  stop3  "

        val settings = state.toSettings()

        assertEquals(listOf("stop1", "stop2", "stop3"), settings.stopStrings)
    }

    fun testStopStringsSingleValue() {
        val state = SettingsState()
        state.stopStrings = "single"

        val settings = state.toSettings()

        assertEquals(listOf("single"), settings.stopStrings)
    }
}
