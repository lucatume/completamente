package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.services.DebugLog
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.XmlSerializerUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Project-scoped cache of past walkthroughs. Persisted via `PersistentStateComponent` so cached
 * entries survive IDE restarts.
 *
 * Eviction policy:
 * - **Cap-at-N** (5 by default): adding a 6th entry drops the oldest by `createdAtMillis`.
 * - **Stale-by-edit**: entries whose any referenced file has changed (mtime mismatch or the
 *   file is gone) are dropped at recall time.
 *
 * The pure logic lives in [WalkthroughCacheLogic]; this class is the IDE-facing shell that
 * captures fingerprints from the VFS and rehydrates `Walkthrough` trees from JSON.
 */
@Service(Service.Level.PROJECT)
@State(name = "completamente.WalkthroughCache", storages = [Storage("completamente-walkthrough-cache.xml")])
class WalkthroughCache(private val project: Project) : PersistentStateComponent<WalkthroughCache.State> {

    /** Mutable JavaBean for `XmlSerializerUtil`. Public so the platform can read/write its fields. */
    class State {
        var entries: MutableList<EntryDTO> = mutableListOf()
    }

    @Volatile private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    /**
     * Add [walkthrough] to the cache, capturing per-file mtimes for stale detection. Cap is
     * applied after insertion. Calls `Json.encodeToString` and `ReadAction.compute` so it's
     * safe to invoke from the EDT or a pooled thread.
     */
    fun put(prompt: String, originFilePath: String, walkthrough: Walkthrough) {
        val newEntry = EntryDTO().also { e ->
            e.id = UUID.randomUUID().toString()
            e.createdAtMillis = System.currentTimeMillis()
            e.prompt = prompt
            e.originFilePath = originFilePath
            e.walkthroughJson = Json.encodeToString(walkthrough)
            e.fileStamps = ReadAction.compute<MutableMap<String, Long>, RuntimeException> {
                captureStamps(WalkthroughCacheLogic.collectReferencedFiles(walkthrough))
            }
        }
        synchronized(state) {
            state.entries = WalkthroughCacheLogic
                .insert(state.entries.toList(), newEntry)
                .toMutableList()
        }
    }

    /**
     * Run stale-eviction against the current VFS, persist the survivors, and return them
     * sorted newest-first. Safe to call from the EDT.
     */
    fun listFresh(): List<EntryDTO> {
        val before = synchronized(state) { state.entries.toList() }
        val fresh = ReadAction.compute<List<EntryDTO>, RuntimeException> {
            WalkthroughCacheLogic.evictStale(before) { relPath -> currentStamp(relPath) }
        }
        if (fresh.size != before.size) {
            synchronized(state) { state.entries = fresh.toMutableList() }
        }
        return fresh.sortedByDescending { it.createdAtMillis }
    }

    /** Decode the cached JSON back to a `Walkthrough`. Returns null on parse failure. */
    fun rehydrate(entry: EntryDTO): Walkthrough? = try {
        Json.decodeFromString<Walkthrough>(entry.walkthroughJson)
    } catch (e: Exception) {
        DebugLog.log("WalkthroughCache failed to rehydrate ${entry.id}: ${e.message}")
        null
    }

    /** Drop a single entry by id (used when rehydration or rebuild fails post-pick). */
    fun remove(id: String) {
        synchronized(state) {
            state.entries.removeAll { it.id == id }
        }
    }

    private fun captureStamps(paths: Set<String>): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>(paths.size)
        for (path in paths) {
            val stamp = currentStamp(path) ?: continue
            out[path] = stamp
        }
        return out
    }

    private fun currentStamp(relPath: String): Long? {
        val file = findFileInContentRoots(relPath) ?: return null
        return file.timeStamp
    }

    private fun findFileInContentRoots(relPath: String): VirtualFile? {
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            val file = root.findFileByRelativePath(relPath)
            if (file != null) return file
        }
        return null
    }

    companion object {
        fun getInstance(project: Project): WalkthroughCache = project.service()
    }
}
