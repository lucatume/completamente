package com.github.lucatume.completamente.services

import com.intellij.openapi.diagnostic.Logger

object DebugLog {

    @PublishedApi
    internal val logger = Logger.getInstance("#completamente.debug")

    val isEnabled: Boolean
        get() = try {
            SettingsState.getInstance().debugLogging
        } catch (_: Exception) {
            false
        }

    fun log(message: String) {
        if (!isEnabled) return
        logger.info("[completamente] $message")
    }

    inline fun <T> timed(label: String, block: () -> T): T {
        if (!isEnabled) return block()
        val start = System.nanoTime()
        try {
            val result = block()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            logger.info("[completamente] $label: ${elapsedMs}ms")
            return result
        } catch (e: Throwable) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            logger.info("[completamente] $label: ${elapsedMs}ms (failed: ${e.javaClass.simpleName})")
            throw e
        }
    }
}
