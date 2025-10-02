package com.github.lucatume.completamente.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.util.Alarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class BackgroundJobs : CoroutineScope, Disposable {
    override val coroutineContext = SupervisorJob()

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var currentJob: Job? = null
    private var pendingJob: Job? = null

    /**
     * Requests a FIM completion with debouncing.
     *
     * If a request is already in flight, the new request is debounced by 100ms.
     * If another request comes in before the timer fires, the previous timer
     * is cancelled and a new 100ms timer starts.
     *
     * @param task The unit of work to execute for the FIM request
     * @param debounceMillis The delay in milliseconds before the request is executed
     */
    fun runWithDebounce(task: suspend CoroutineScope.() -> Unit, debounceMillis: Long = 100) {
        if (currentJob != null) {
            pendingJob?.cancel()
            pendingJob = launch { delay(debounceMillis); task() }
            return
        }

        currentJob = launch { task() }
    }

    override fun dispose() {
        coroutineContext[Job]?.cancel()
    }
}
