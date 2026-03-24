package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import java.util.logging.Handler
import java.util.logging.LogRecord

class DebugLogTest : BaseCompletionTest() {

    private val logRecords = mutableListOf<LogRecord>()
    private val capturingHandler = object : Handler() {
        override fun publish(record: LogRecord) { logRecords.add(record) }
        override fun flush() {}
        override fun close() {}
    }

    override fun setUp() {
        super.setUp()
        logRecords.clear()
        val julLogger = java.util.logging.Logger.getLogger("#completamente.debug")
        julLogger.addHandler(capturingHandler)
    }

    override fun tearDown() {
        try {
            val julLogger = java.util.logging.Logger.getLogger("#completamente.debug")
            julLogger.removeHandler(capturingHandler)
            SettingsState.getInstance().debugLogging = false
        } finally {
            super.tearDown()
        }
    }

    fun testTimedReturnsBlockValueWhenDisabled() {
        val result = DebugLog.timed("test label") { 42 }
        assertEquals(42, result)
    }

    fun testTimedReturnsBlockValueWhenEnabled() {
        SettingsState.getInstance().debugLogging = true
        val result = DebugLog.timed("test label") { "hello" }
        assertEquals("hello", result)
    }

    fun testIsEnabledMatchesSetting() {
        assertFalse(DebugLog.isEnabled)
        SettingsState.getInstance().debugLogging = true
        assertTrue(DebugLog.isEnabled)
    }

    fun testLogWritesWhenEnabled() {
        SettingsState.getInstance().debugLogging = true
        DebugLog.log("test message")
        val match = logRecords.any { it.message.contains("[completamente] test message") }
        assertTrue("Expected log record with '[completamente] test message'", match)
    }

    fun testLogSuppressedWhenDisabled() {
        DebugLog.log("should not appear")
        val match = logRecords.any { it.message.contains("should not appear") }
        assertFalse("Expected no log record when disabled", match)
    }

    fun testTimedLogsElapsedWhenEnabled() {
        SettingsState.getInstance().debugLogging = true
        DebugLog.timed("my-op") { Thread.sleep(1) }
        val match = logRecords.any { it.message.contains("[completamente] my-op:") && it.message.contains("ms") }
        assertTrue("Expected timing log for 'my-op'", match)
    }

    fun testTimedDoesNotLogWhenDisabled() {
        DebugLog.timed("silent-op") { 1 + 1 }
        val match = logRecords.any { it.message.contains("silent-op") }
        assertFalse("Expected no timing log when disabled", match)
    }

    fun testTimedPropagatesException() {
        SettingsState.getInstance().debugLogging = true
        try {
            DebugLog.timed("failing-op") { throw IllegalStateException("boom") }
            fail("Expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        val match = logRecords.any { it.message.contains("failing-op") && it.message.contains("failed: IllegalStateException") }
        assertTrue("Expected timing log with failure info", match)
    }

    fun testTimedPropagatesExceptionWhenDisabled() {
        try {
            DebugLog.timed("disabled-fail") { throw RuntimeException("kaboom") }
            fail("Expected RuntimeException to propagate")
        } catch (e: RuntimeException) {
            assertEquals("kaboom", e.message)
        }
        val match = logRecords.any { it.message.contains("disabled-fail") }
        assertFalse("Expected no log when disabled even on exception", match)
    }

    fun testTimedWithNullReturn() {
        SettingsState.getInstance().debugLogging = true
        val result: String? = DebugLog.timed("null-op") { null }
        assertNull(result)
        val match = logRecords.any { it.message.contains("[completamente] null-op:") && it.message.contains("ms") }
        assertTrue("Expected timing log even for null return", match)
    }

    fun testLogPrefixFormat() {
        SettingsState.getInstance().debugLogging = true
        DebugLog.log("format check")
        val record = logRecords.find { it.message.contains("format check") }
        assertNotNull("Expected log record", record)
        assertTrue("Expected [completamente] prefix", record!!.message.startsWith("[completamente] "))
    }
}
