package com.github.lucatume.completamente.services

import com.intellij.openapi.components.Service

/**
 * Service that maintains the suggestion cache state.
 * Translation of g:cache_data and g:cache_lru_order from llama.vim.
 *
 * The cache stores raw JSON responses from the llama.cpp server, keyed by
 * a SHA256 hash of the local context (prefix + middle + 'Î' + suffix).
 *
 * @property data The cache data mapping context hash to raw JSON response.
 * @property lruOrder List maintaining LRU (Least Recently Used) order of keys.
 *                    First element is least recently used, last is most recently used.
 */
@Service(Service.Level.PROJECT)
class SuggestionCache {
    val data: MutableMap<String, String> = mutableMapOf()
    val lruOrder: MutableList<String> = mutableListOf()
}
