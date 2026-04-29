package com.github.lucatume.completamente.walkthrough

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-scoped service that owns the at-most-one active [WalkthroughSession].
 *
 * Implements [Disposable] so the IntelliJ container parents session cleanup to the project
 * lifecycle — closing the project disposes any active session via the chain
 * `Disposer.register(service, sessionDisposable)`.
 *
 * The [nextRunId] counter is used by the action layer to detect "trigger again while agent
 * running" races: each in-flight task captures the run id at dispatch time; when the task
 * completes, it compares the captured id to the current id and no-ops if they differ.
 */
@Service(Service.Level.PROJECT)
class WalkthroughService(private val project: Project) : Disposable {

    @Volatile var active: WalkthroughSession? = null
        private set

    /** Disposable parented to the in-flight agent task — cancelled on trigger-again. */
    @Volatile var inFlight: Disposable? = null
        private set

    private val runIdCounter = AtomicLong(0)

    /**
     * Atomically advances the run id and returns the new value. Pair this with a check at
     * task-completion time to detect re-triggers.
     */
    fun nextRunId(): Long = runIdCounter.incrementAndGet()

    fun currentRunId(): Long = runIdCounter.get()

    /**
     * Replace the active session with [session]. Disposes the previous active session, if any.
     * The new session must already be parented to this service (via [Disposer.register]) so
     * project close runs its cleanup.
     */
    fun setActive(session: WalkthroughSession?) {
        val previous = active
        active = session
        if (previous != null && previous !== session) {
            Disposer.dispose(previous)
        }
    }

    /** Cancel and dispose the currently in-flight agent task, if any. */
    fun cancelInFlight() {
        val d = inFlight
        inFlight = null
        if (d != null) Disposer.dispose(d)
    }

    fun setInFlight(disposable: Disposable?) {
        cancelInFlight()
        inFlight = disposable
    }

    override fun dispose() {
        cancelInFlight()
        setActive(null)
    }

    companion object {
        fun getInstance(project: Project): WalkthroughService = project.service()
    }
}
