package com.github.lucatume.completamente.walkthrough

/**
 * Persistence DTO for one cached walkthrough. JavaBean shape so [WalkthroughCache.State] can
 * round-trip through IntelliJ's `XmlSerializerUtil`. Fields are mutable on purpose.
 *
 * `walkthroughJson` holds a kotlinx-serialization JSON encoding of the parsed [Walkthrough]
 * tree — the persistence boundary intentionally ignores the LLM wire format so future changes
 * to the parser don't invalidate cached entries.
 *
 * `fileStamps` captures every project-relative file path the walkthrough references with that
 * file's `VirtualFile.timeStamp` (OS mtime) at cache-insert time. Used by [WalkthroughCacheLogic.evictStale]
 * to drop entries whose underlying files have changed.
 */
class EntryDTO {
    var id: String = ""
    var createdAtMillis: Long = 0L
    var prompt: String = ""
    var originFilePath: String = ""
    var walkthroughJson: String = ""
    var fileStamps: MutableMap<String, Long> = mutableMapOf()
}

/**
 * Pure functions over the cache's state. No IDE deps — exhaustive unit tests live in
 * `WalkthroughCacheLogicTest`.
 */
object WalkthroughCacheLogic {

    const val DEFAULT_CAP = 5

    /**
     * Prepend [new] and trim to [cap], evicting oldest-by-`createdAtMillis` first. The list is
     * not assumed to be sorted on input — callers may pass an arbitrarily-ordered list (e.g. as
     * read back from XML persistence).
     */
    fun insert(existing: List<EntryDTO>, new: EntryDTO, cap: Int = DEFAULT_CAP): List<EntryDTO> {
        val combined = ArrayList<EntryDTO>(existing.size + 1)
        combined += new
        combined += existing
        if (combined.size <= cap) return combined
        // Drop the oldest by createdAtMillis until we're at cap. The freshly-inserted entry is
        // newest by construction, so it always survives.
        return combined.sortedByDescending { it.createdAtMillis }.take(cap)
    }

    /**
     * Drop entries whose any tracked file's current stamp is null (file gone) or differs from
     * the captured stamp. Entries with empty `fileStamps` are kept — there's nothing to compare
     * against, and the walkthrough itself decides whether its (zero) referenced files have
     * changed (vacuously: no).
     */
    fun evictStale(
        entries: List<EntryDTO>,
        currentStamp: (relativePath: String) -> Long?
    ): List<EntryDTO> = entries.filter { entry ->
        entry.fileStamps.all { (path, captured) ->
            val now = currentStamp(path)
            now != null && now == captured
        }
    }

    /**
     * Walk the tree and collect every distinct project-relative file path referenced by any
     * step. Order is unspecified.
     */
    fun collectReferencedFiles(walkthrough: Walkthrough): Set<String> {
        val out = LinkedHashSet<String>()
        fun visit(step: WalkthroughStep) {
            out += step.range.file
            step.children.forEach(::visit)
        }
        visit(walkthrough.root)
        return out
    }
}
