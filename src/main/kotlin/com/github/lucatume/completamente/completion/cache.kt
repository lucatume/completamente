package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.SuggestionCache
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * Compute the SHA256 hash of a string.
 *
 * @param input The string to hash.
 * @return The hex-encoded SHA256 hash.
 */
fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Compute the context hash for cache lookup.
 * Translation of the hash computation in llama.vim:
 * `sha256(l:prefix . l:middle . 'Î' . l:suffix)`
 *
 * @param prefix The prefix text (lines before cursor).
 * @param middle The middle text (current line up to cursor).
 * @param suffix The suffix text (rest of current line and lines after).
 * @return The SHA256 hash of the context.
 */
fun computeContextHash(prefix: String, middle: String, suffix: String): String {
    return sha256(prefix + middle + "Î" + suffix)
}

/**
 * Compute multiple context hashes by progressively trimming the prefix.
 * This allows finding cached completions where the prefix has shifted
 * (e.g., when scrolling down from where the original completion was generated).
 *
 * Translation of the hash computation loop in llama.vim llama#fim (lines 593-605):
 * ```vim
 * let l:hashes = []
 * call add(l:hashes, sha256(l:prefix . l:middle . 'Î' . l:suffix))
 * let l:prefix_trim = l:prefix
 * for i in range(3)
 *     let l:prefix_trim = substitute(l:prefix_trim, '^[^\n]*\n', '', '')
 *     if empty(l:prefix_trim)
 *         break
 *     endif
 *     call add(l:hashes, sha256(l:prefix_trim . l:middle . 'Î' . l:suffix))
 * endfor
 * ```
 *
 * @param prefix The prefix text (lines before cursor).
 * @param middle The middle text (current line up to cursor).
 * @param suffix The suffix text (rest of current line and lines after).
 * @return List of hashes, with the full context hash first, then progressively trimmed versions.
 */
fun computeContextHashes(prefix: String, middle: String, suffix: String): List<String> {
    val hashes = mutableListOf<String>()

    // Add the full context hash
    hashes.add(computeContextHash(prefix, middle, suffix))

    // Add hashes with progressively trimmed prefix (up to 3 lines)
    var prefixTrim = prefix
    for (i in 0 until 3) {
        // Remove the first line from the prefix
        val newlineIndex = prefixTrim.indexOf('\n')
        if (newlineIndex < 0) {
            break
        }
        prefixTrim = prefixTrim.substring(newlineIndex + 1)
        if (prefixTrim.isEmpty()) {
            break
        }
        hashes.add(computeContextHash(prefixTrim, middle, suffix))
    }

    return hashes
}

/**
 * Insert a cache entry, evicting the LRU entry if the cache exceeds capacity.
 *
 * Translation of llama.vim s:cache_insert function.
 *
 * @param cache The suggestion cache service to update.
 * @param key The context hash key.
 * @param value The raw JSON response to store.
 * @param maxCacheKeys The maximum number of entries to keep in the cache.
 */
fun cacheInsert(
    cache: SuggestionCache,
    key: String,
    value: String,
    maxCacheKeys: Int
) {
    // Check if we need to evict an entry
    if (cache.data.size >= maxCacheKeys) {
        // Get the least recently used key (first in order list)
        if (cache.lruOrder.isNotEmpty()) {
            val lruKey = cache.lruOrder.removeAt(0)
            cache.data.remove(lruKey)
        }
    }

    // Update the cache data
    cache.data[key] = value

    // Update LRU order - remove key if it exists and add to end (most recent)
    cache.lruOrder.remove(key)
    cache.lruOrder.add(key)
}

/**
 * Get a cache entry by key and update its LRU order.
 *
 * Translation of llama.vim s:cache_get function.
 *
 * @param cache The suggestion cache service to query.
 * @param key The context hash key.
 * @return The raw JSON response if found, null otherwise.
 */
fun cacheGet(cache: SuggestionCache, key: String): String? {
    val entry = cache.data[key] ?: return null

    // Update LRU order - remove key if it exists and add to end (most recent)
    cache.lruOrder.remove(key)
    cache.lruOrder.add(key)

    return entry
}

/**
 * Check if a cache entry exists for any of the provided hashes.
 * This is used to determine if a new FIM request should be sent.
 *
 * Translation of the cache check in llama.vim llama#fim (lines 608-614):
 * ```vim
 * if a:use_cache
 *     for l:hash in l:hashes
 *         if s:cache_get(l:hash) != v:null
 *             return
 *         endif
 *     endfor
 * endif
 * ```
 *
 * @param cache The suggestion cache service to query.
 * @param hashes List of context hashes to check.
 * @return True if any hash has a cached entry, false otherwise.
 */
fun cacheHasAny(cache: SuggestionCache, hashes: List<String>): Boolean {
    for (hash in hashes) {
        if (cacheGet(cache, hash) != null) {
            return true
        }
    }
    return false
}

/**
 * Extract the "content" field from a raw JSON response.
 *
 * Translation of llama.vim json_decode and content access:
 * ```vim
 * let l:response = json_decode(l:response_cached)
 * l:response['content']
 * ```
 *
 * @param raw The raw JSON response from the llama.cpp server.
 * @return The content string if found, null otherwise.
 */
fun extractContentFromResponse(raw: String): String? {
    return try {
        val json = Json.parseToJsonElement(raw).jsonObject
        json["content"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}

/**
 * Create a new JSON string with the content field updated.
 * This creates a local copy and does NOT modify the cache.
 *
 * Translation of llama.vim content update and json_encode:
 * ```vim
 * let l:response = json_decode(l:response_cached)
 * let l:response['content'] = l:response['content'][i + 1:]
 * let l:raw = json_encode(l:response)
 * ```
 *
 * @param raw The original raw JSON response.
 * @param newContent The new content value to set.
 * @return A new JSON string with the updated content field.
 */
fun updateContentInResponse(raw: String, newContent: String): String {
    return try {
        val json = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        json["content"] = JsonPrimitive(newContent)
        Json.encodeToString(JsonObject.serializer(), JsonObject(json))
    } catch (e: Exception) {
        raw
    }
}

/**
 * Result of a cache lookup in tryGetCachedSuggestion.
 *
 * @property raw The raw JSON response from the cache.
 * @property trimmedContent If this is a partial match, the content trimmed to account for typed characters.
 *                          Null if this is an exact match.
 */
data class CacheLookupResult(
    val raw: String,
    val trimmedContent: String? = null
)

/**
 * Try to find a cached suggestion for the current context.
 * First checks for an exact match, then looks back to find partial matches
 * where typed characters match the beginning of a cached completion.
 *
 * Translation of llama.vim s:fim_try_hint cache lookup logic (lines 814-853):
 * ```vim
 * let l:hash = sha256(l:prefix . l:middle . 'Î' . l:suffix)
 * let l:raw = s:cache_get(l:hash)
 *
 * " ... or if there is a cached completion nearby (10 characters behind)
 * if l:raw == v:null
 *     let l:pm = l:prefix . l:middle
 *     let l:best = 0
 *     for i in range(128)
 *         let l:removed = l:pm[-(1 + i):]
 *         let l:ctx_new = l:pm[:-(2 + i)] . 'Î' . l:suffix
 *         let l:hash_new = sha256(l:ctx_new)
 *         let l:response_cached = s:cache_get(l:hash_new)
 *         if l:response_cached != v:null
 *             if l:response_cached == ""
 *                 continue
 *             endif
 *             let l:response = json_decode(l:response_cached)
 *             if l:response['content'][0:i] !=# l:removed
 *                 continue
 *             endif
 *             let l:response['content'] = l:response['content'][i + 1:]
 *             if len(l:response['content']) > 0
 *                 if l:raw == v:null
 *                     let l:raw = json_encode(l:response)
 *                 elseif len(l:response['content']) > l:best
 *                     let l:best = len(l:response['content'])
 *                     let l:raw = json_encode(l:response)
 *                 endif
 *             endif
 *         endif
 *     endfor
 * endif
 * ```
 *
 * @param cache The suggestion cache service to query.
 * @param localContext The local context containing prefix, middle, and suffix.
 * @return The cache lookup result if found, null otherwise.
 */
fun tryGetCachedSuggestion(
    cache: SuggestionCache,
    localContext: LocalContext
): CacheLookupResult? {
    val prefix = localContext.prefix
    val middle = localContext.middle
    val suffix = localContext.suffix

    // First, check for an exact match
    val hash = computeContextHash(prefix, middle, suffix)
    val exactMatch = cacheGet(cache, hash)
    if (exactMatch != null) {
        return CacheLookupResult(exactMatch)
    }

    // Look back to find partial matches
    val pm = prefix + middle
    var bestResult: CacheLookupResult? = null
    var bestLength = 0

    for (i in 0 until minOf(128, pm.length)) {
        // Characters that were typed after the cached position
        val removed = pm.substring(pm.length - 1 - i)

        // Build the context as it was when the completion was cached
        val ctxNew = pm.substring(0, pm.length - 1 - i) + "Î" + suffix
        val hashNew = sha256(ctxNew)

        val cachedRaw = cacheGet(cache, hashNew) ?: continue

        // Skip empty responses
        if (cachedRaw.isEmpty()) {
            continue
        }

        // Extract content from the cached response
        val cachedContent = extractContentFromResponse(cachedRaw) ?: continue

        // Check if the typed characters match the beginning of the cached completion
        // vim: l:response['content'][0:i] !=# l:removed
        // Note: vim's [0:i] is inclusive on both ends, so it returns i+1 characters
        if (cachedContent.length <= i) {
            continue
        }
        val cachedPrefix = cachedContent.substring(0, i + 1)
        if (cachedPrefix != removed) {
            continue
        }

        // Trim the cached completion to remove the already-typed characters
        val trimmedContent = cachedContent.substring(i + 1)
        if (trimmedContent.isNotEmpty()) {
            // Keep the best (longest) match
            if (bestResult == null || trimmedContent.length > bestLength) {
                bestLength = trimmedContent.length
                val updatedRaw = updateContentInResponse(cachedRaw, trimmedContent)
                bestResult = CacheLookupResult(
                    raw = updatedRaw,
                    trimmedContent = trimmedContent
                )
            }
        }
    }

    return bestResult
}

/**
 * Insert all context hashes for a response into the cache.
 * This is called when a new completion response is received.
 *
 * Translation of the cache insertion in llama.vim s:fim_on_response (lines 766-768):
 * ```vim
 * for l:hash in a:hashes
 *     call s:cache_insert(l:hash, l:raw)
 * endfor
 * ```
 *
 * @param cache The suggestion cache service to update.
 * @param hashes List of context hashes to associate with this response.
 * @param raw The raw JSON response from the llama.cpp server.
 * @param maxCacheKeys The maximum number of entries to keep in the cache.
 */
fun cacheInsertResponse(
    cache: SuggestionCache,
    hashes: List<String>,
    raw: String,
    maxCacheKeys: Int
) {
    for (hash in hashes) {
        cacheInsert(cache, hash, raw, maxCacheKeys)
    }
}

/**
 * Get the number of entries in the cache.
 *
 * @param cache The suggestion cache service to query.
 * @return The number of cached entries.
 */
fun cacheSize(cache: SuggestionCache): Int = cache.data.size

/**
 * Clear all entries from the cache.
 *
 * @param cache The suggestion cache service to clear.
 */
fun cacheClear(cache: SuggestionCache) {
    cache.data.clear()
    cache.lruOrder.clear()
}
