package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.completion.InfillClient
import com.github.lucatume.completamente.completion.InfillExtraChunk
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Project-level service that warms the llama.cpp KV cache with [InfillExtraChunk] data.
 *
 * Debounces rapid warmup requests so only the last one fires after 500 ms of inactivity.
 * The actual HTTP request is sent on a pooled background thread via [Alarm.ThreadToUse.POOLED_THREAD].
 */
@Service(Service.Level.PROJECT)
class CacheWarmingService(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var lastWarmedExtras: List<InfillExtraChunk> = emptyList()

    /**
     * Schedules a cache-warming request after a 500 ms debounce period.
     * If called again within 500 ms, the previous pending request is cancelled.
     */
    fun scheduleWarmup(extra: List<InfillExtraChunk>) {
        // Skip scheduling if the extras haven't changed (deduplication).
        if (extra == lastWarmedExtras) return

        // Store immediately so getLastWarmedExtras reflects intent even before the request fires.
        lastWarmedExtras = extra
        alarm.cancelAllRequests()
        alarm.addRequest({
            try {
                val serverUrl = SettingsState.getInstance().toSettings().serverUrl
                val client = InfillClient(serverUrl)
                client.sendCacheWarming(extra)
            } catch (_: Exception) {
                // Fire and forget -- errors are silently ignored.
            }
        }, 500)
    }

    /**
     * Returns the extras from the most recent [scheduleWarmup] call
     * (even if the HTTP request has not yet fired or completed).
     */
    fun getLastWarmedExtras(): List<InfillExtraChunk> = lastWarmedExtras

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
