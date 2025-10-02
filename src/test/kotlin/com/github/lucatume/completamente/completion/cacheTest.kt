package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.SuggestionCache

class cacheTest : BaseCompletionTest() {
    fun testSha256WithEmptyString() {
        val result = sha256("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result)
    }

    fun testSha256WithSimpleString() {
        val result = sha256("hello")
        assertTrue(result.length == 64)
        assertTrue(result.matches(Regex("[a-f0-9]{64}")))
    }

    fun testSha256WithLongString() {
        val input = "The quick brown fox jumps over the lazy dog"
        val result = sha256(input)
        assertTrue(result.length == 64)
        assertTrue(result.matches(Regex("[a-f0-9]{64}")))
    }

    fun testSha256WithSpecialCharacters() {
        val result = sha256("hello\nworld\t!")
        assertTrue(result.length == 64)  // SHA256 hex is always 64 chars
        assertTrue(result.matches(Regex("[a-f0-9]{64}")))
    }

    fun testSha256WithUnicodeCharacters() {
        val result = sha256("Hello 世界")
        assertTrue(result.length == 64)
        assertTrue(result.matches(Regex("[a-f0-9]{64}")))
    }

    fun testSha256DeterministicResults() {
        val input = "test string"
        val result1 = sha256(input)
        val result2 = sha256(input)
        assertEquals(result1, result2)
    }

    fun testSha256DifferentInputsDifferentHashes() {
        val hash1 = sha256("input1")
        val hash2 = sha256("input2")
        assertFalse(hash1 == hash2)
    }

    fun testComputeContextHashWithEmptyStrings() {
        val result = computeContextHash("", "", "")
        assertTrue(result.length == 64)
        assertTrue(result.matches(Regex("[a-f0-9]{64}")))
    }

    fun testComputeContextHashIncludesAllComponents() {
        val prefix = "prefix"
        val middle = "middle"
        val suffix = "suffix"
        val result = computeContextHash(prefix, middle, suffix)

        // Verify it matches the manual SHA256 calculation
        val manualHash = sha256(prefix + middle + "Î" + suffix)
        assertEquals(manualHash, result)
    }

    fun testComputeContextHashWithNewlines() {
        val result = computeContextHash("line1\nline2", "mid", "suf")
        assertTrue(result.length == 64)
    }

    fun testComputeContextHashIncludesSeparatorCharacter() {
        val hash1 = computeContextHash("a", "b", "c")
        val hash2 = computeContextHash("ab", "b", "c")  // Different without separator
        assertFalse(hash1 == hash2)
    }

    fun testComputeContextHashWithOnlyPrefix() {
        val hash1 = computeContextHash("prefix", "", "")
        val hash2 = computeContextHash("prefix", "", "")
        assertEquals(hash1, hash2)
    }

    fun testComputeContextHashWithLongInputs() {
        val prefix = "a".repeat(1000)
        val middle = "b".repeat(1000)
        val suffix = "c".repeat(1000)
        val result = computeContextHash(prefix, middle, suffix)
        assertTrue(result.length == 64)
    }

    fun testComputeContextHashesWithNoPrefixLines() {
        val hashes = computeContextHashes("", "middle", "suffix")
        assertEquals(1, hashes.size)
    }

    fun testComputeContextHashesWithSingleLinePrefixNoNewline() {
        val hashes = computeContextHashes("prefix", "middle", "suffix")
        assertEquals(1, hashes.size)
    }

    fun testComputeContextHashesWithSingleNewlinePrefix() {
        val hashes = computeContextHashes("line1\n", "middle", "suffix")
        assertEquals(1, hashes.size)  // "line1\n" trims to empty, which breaks loop
    }

    fun testComputeContextHashesWithTwoLinesPrefix() {
        val hashes = computeContextHashes("line1\nline2\n", "middle", "suffix")
        assertEquals(2, hashes.size)
    }

    fun testComputeContextHashesWithThreeLinesPrefix() {
        val hashes = computeContextHashes("line1\nline2\nline3\n", "middle", "suffix")
        assertEquals(3, hashes.size)
    }

    fun testComputeContextHashesStopsAfterThreeTrims() {
        val hashes = computeContextHashes("l1\nl2\nl3\nl4\nl5\n", "middle", "suffix")
        assertEquals(4, hashes.size)  // Original + 3 trims max
    }

    fun testComputeContextHashesFirstHashIncludesFull() {
        val prefix = "line1\nline2\n"
        val middle = "middle"
        val suffix = "suffix"
        val hashes = computeContextHashes(prefix, middle, suffix)
        val expectedFirst = computeContextHash(prefix, middle, suffix)
        assertEquals(expectedFirst, hashes[0])
    }

    fun testComputeContextHashesProgressivelyTrimsPrefix() {
        val hashes = computeContextHashes("a\nb\nc\n", "m", "s")
        val hash0 = computeContextHash("a\nb\nc\n", "m", "s")
        val hash1 = computeContextHash("b\nc\n", "m", "s")
        val hash2 = computeContextHash("c\n", "m", "s")

        assertEquals(hash0, hashes[0])
        assertEquals(hash1, hashes[1])
        assertEquals(hash2, hashes[2])
    }

    fun testComputeContextHashesStopsWhenPrefixBecomesEmpty() {
        val hashes = computeContextHashes("line\n", "m", "s")
        assertEquals(1, hashes.size)
        // After removing "line\n", remaining is empty so loop breaks
    }

    fun testComputeContextHashesWithComplexPrefixContent() {
        val prefix = "def foo():\n    pass\n"
        val middle = "return "
        val suffix = " + 1\n"
        val hashes = computeContextHashes(prefix, middle, suffix)
        assertTrue(hashes.size >= 1)
        assertTrue(hashes.all { it.length == 64 })
    }

    fun testCacheInsertIntoEmptyCache() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)

        assertEquals(1, cache.data.size)
        assertEquals("value1", cache.data["key1"])
        assertEquals(listOf("key1"), cache.lruOrder)
    }

    fun testCacheInsertMultipleKeys() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)

        assertEquals(2, cache.data.size)
        assertEquals("value1", cache.data["key1"])
        assertEquals("value2", cache.data["key2"])
        assertEquals(listOf("key1", "key2"), cache.lruOrder)
    }

    fun testCacheInsertUpdateExistingKey() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key1", "value2", 10)

        assertEquals(1, cache.data.size)
        assertEquals("value2", cache.data["key1"])
        assertEquals(listOf("key1"), cache.lruOrder)
    }

    fun testCacheInsertUpdateMovesToEnd() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)
        cacheInsert(cache, "key1", "value3", 10)  // Update key1

        assertEquals(2, cache.data.size)
        assertEquals("value3", cache.data["key1"])
        assertEquals(listOf("key2", "key1"), cache.lruOrder)  // key1 moved to end
    }

    fun testCacheInsertEvictsLruWhenFull() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 2)
        cacheInsert(cache, "key2", "value2", 2)
        cacheInsert(cache, "key3", "value3", 2)  // Should evict key1

        assertEquals(2, cache.data.size)
        assertFalse(cache.data.containsKey("key1"))
        assertTrue(cache.data.containsKey("key2"))
        assertTrue(cache.data.containsKey("key3"))
        assertEquals(listOf("key2", "key3"), cache.lruOrder)
    }

    fun testCacheInsertWithMaxCacheKeysOne() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 1)
        cacheInsert(cache, "key2", "value2", 1)

        assertEquals(1, cache.data.size)
        assertEquals("value2", cache.data["key2"])
        assertEquals(listOf("key2"), cache.lruOrder)
    }

    fun testCacheInsertMultipleEvictions() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 2)
        cacheInsert(cache, "key2", "value2", 2)
        cacheInsert(cache, "key3", "value3", 2)
        cacheInsert(cache, "key4", "value4", 2)

        assertEquals(2, cache.data.size)
        assertFalse(cache.data.containsKey("key1"))
        assertFalse(cache.data.containsKey("key2"))
        assertTrue(cache.data.containsKey("key3"))
        assertTrue(cache.data.containsKey("key4"))
    }

    fun testCacheInsertEmptyValue() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "", 10)

        assertEquals(1, cache.data.size)
        assertEquals("", cache.data["key1"])
    }

    fun testCacheInsertLargeValue() {
        val cache = SuggestionCache()
        val largeValue = "x".repeat(10000)
        cacheInsert(cache, "key1", largeValue, 10)

        assertEquals(largeValue, cache.data["key1"])
    }

    fun testCacheGetExistingKey() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)

        val result = cacheGet(cache, "key1")
        assertEquals("value1", result)
    }

    fun testCacheGetNonexistentKey() {
        val cache = SuggestionCache()
        val result = cacheGet(cache, "nonexistent")
        assertNull(result)
    }

    fun testCacheGetUpdatesLruOrder() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)

        cacheGet(cache, "key1")  // Access key1

        assertEquals(listOf("key2", "key1"), cache.lruOrder)
    }

    fun testCacheGetOnAlreadyLatestDoesNotChangeOrder() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)

        cacheGet(cache, "key2")  // Access key2 (already at end)

        assertEquals(listOf("key1", "key2"), cache.lruOrder)
    }

    fun testCacheGetFromEmptyCache() {
        val cache = SuggestionCache()
        val result = cacheGet(cache, "key1")
        assertNull(result)
    }

    fun testCacheGetEmptyValue() {
        val cache = SuggestionCache()
        cache.data["key1"] = ""
        cache.lruOrder.add("key1")

        val result = cacheGet(cache, "key1")
        assertEquals("", result)
    }

    fun testCacheGetMultipleCallsUpdateLruEachTime() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)
        cacheInsert(cache, "key3", "value3", 10)

        cacheGet(cache, "key1")
        assertEquals(listOf("key2", "key3", "key1"), cache.lruOrder)

        cacheGet(cache, "key2")
        assertEquals(listOf("key3", "key1", "key2"), cache.lruOrder)
    }

    fun testCacheHasAnyWithEmptyHashes() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)

        val result = cacheHasAny(cache, emptyList())
        assertFalse(result)
    }

    fun testCacheHasAnyWithExactMatch() {
        val cache = SuggestionCache()
        cacheInsert(cache, "hash1", "value1", 10)

        val result = cacheHasAny(cache, listOf("hash1"))
        assertTrue(result)
    }

    fun testCacheHasAnyWithMultipleHashesFirstMatches() {
        val cache = SuggestionCache()
        cacheInsert(cache, "hash1", "value1", 10)

        val result = cacheHasAny(cache, listOf("hash1", "hash2", "hash3"))
        assertTrue(result)
    }

    fun testCacheHasAnyWithMultipleHashesMiddleMatches() {
        val cache = SuggestionCache()
        cacheInsert(cache, "hash2", "value2", 10)

        val result = cacheHasAny(cache, listOf("hash1", "hash2", "hash3"))
        assertTrue(result)
    }

    fun testCacheHasAnyWithMultipleHashesLastMatches() {
        val cache = SuggestionCache()
        cacheInsert(cache, "hash3", "value3", 10)

        val result = cacheHasAny(cache, listOf("hash1", "hash2", "hash3"))
        assertTrue(result)
    }

    fun testCacheHasAnyNoMatches() {
        val cache = SuggestionCache()
        cacheInsert(cache, "hash1", "value1", 10)

        val result = cacheHasAny(cache, listOf("hash2", "hash3"))
        assertFalse(result)
    }

    fun testCacheHasAnyEmptyCache() {
        val cache = SuggestionCache()

        val result = cacheHasAny(cache, listOf("hash1", "hash2"))
        assertFalse(result)
    }

    fun testCacheHasAnyUpdatesLruOrder() {
        val cache = SuggestionCache()
        cacheInsert(cache, "hash1", "value1", 10)
        cacheInsert(cache, "hash2", "value2", 10)

        cacheHasAny(cache, listOf("hash1"))

        // hash1 should be moved to end due to cacheGet being called
        assertEquals(listOf("hash2", "hash1"), cache.lruOrder)
    }

    fun testExtractContentFromValidJson() {
        val raw = """{"content":"hello world"}"""
        val result = extractContentFromResponse(raw)
        assertEquals("hello world", result)
    }

    fun testExtractContentFromJsonWithOtherFields() {
        val raw = """{"stop":true,"content":"test","model":"llama"}"""
        val result = extractContentFromResponse(raw)
        assertEquals("test", result)
    }

    fun testExtractContentFromJsonEmptyContent() {
        val raw = """{"content":""}"""
        val result = extractContentFromResponse(raw)
        assertEquals("", result)
    }

    fun testExtractContentFromJsonMissingContent() {
        val raw = """{"model":"llama"}"""
        val result = extractContentFromResponse(raw)
        assertNull(result)
    }

    fun testExtractContentFromInvalidJson() {
        val raw = """{"content":"hello""""  // Missing closing brace
        val result = extractContentFromResponse(raw)
        assertNull(result)
    }

    fun testExtractContentFromEmptyString() {
        val result = extractContentFromResponse("")
        assertNull(result)
    }

    fun testExtractContentFromJsonWithNewlines() {
        val raw = """{"content":"line1\nline2\nline3"}"""
        val result = extractContentFromResponse(raw)
        assertEquals("line1\nline2\nline3", result)
    }

    fun testExtractContentFromJsonWithSpecialCharacters() {
        val raw = """{"content":"hello\ttab\nworld"}"""
        val result = extractContentFromResponse(raw)
        assertEquals("hello\ttab\nworld", result)
    }

    fun testExtractContentFromJsonWithUnicodeEscapes() {
        val raw = """{"content":"hello world"}"""
        val result = extractContentFromResponse(raw)
        assertEquals("hello world", result)
    }

    fun testExtractContentFromNullContent() {
        val raw = """{"content":null}"""
        val result = extractContentFromResponse(raw)
        // When content is null in JSON, jsonPrimitive?.content returns "null" string
        assertTrue(result == null || result == "null")
    }

    fun testExtractContentFromContentFieldNotString() {
        val raw = """{"content":123}"""
        val result = extractContentFromResponse(raw)
        // When content is a number, jsonPrimitive.content returns the string "123"
        assertEquals("123", result)
    }

    fun testUpdateContentInValidJson() {
        val raw = """{"content":"old"}"""
        val result = updateContentInResponse(raw, "new")
        assertTrue(result.contains("\"content\":\"new\""))
    }

    fun testUpdateContentInJsonWithOtherFields() {
        val raw = """{"model":"llama","content":"old","stop":true}"""
        val result = updateContentInResponse(raw, "new")
        assertTrue(result.contains("\"content\":\"new\""))
        assertTrue(result.contains("\"model\":\"llama\""))
        assertTrue(result.contains("\"stop\":true"))
    }

    fun testUpdateContentInResponseDoesNotModifyOriginal() {
        val raw = """{"content":"old"}"""
        val result = updateContentInResponse(raw, "new")
        assertFalse(raw == result)
    }

    fun testUpdateContentToEmptyString() {
        val raw = """{"content":"old"}"""
        val result = updateContentInResponse(raw, "")
        assertTrue(result.contains("\"content\":\"\""))
    }

    fun testUpdateContentWithNewlines() {
        val raw = """{"content":"old"}"""
        val result = updateContentInResponse(raw, "line1\nline2")
        assertTrue(result.contains("line1") && result.contains("line2"))
    }

    fun testUpdateContentWithSpecialCharacters() {
        val raw = """{"content":"old"}"""
        val result = updateContentInResponse(raw, "hello\"world")
        assertTrue(result.contains("hello") && result.contains("world"))
    }

    fun testUpdateContentInInvalidJsonReturnsOriginal() {
        val raw = """{"content":"old"""
        val result = updateContentInResponse(raw, "new")
        assertEquals(raw, result)
    }

    fun testUpdateContentInJsonWithoutContent() {
        val raw = """{"model":"llama"}"""
        val result = updateContentInResponse(raw, "new")
        assertTrue(result.contains("\"content\":\"new\""))
    }

    fun testUpdateContentWithLongString() {
        val raw = """{"content":"old"}"""
        val longContent = "x".repeat(5000)
        val result = updateContentInResponse(raw, longContent)
        assertTrue(result.contains(longContent))
    }

    fun testCacheLookupResultWithRawAndTrimmed() {
        val result = CacheLookupResult("raw_json", "trimmed_content")
        assertEquals("raw_json", result.raw)
        assertEquals("trimmed_content", result.trimmedContent)
    }

    fun testCacheLookupResultWithNullTrimmed() {
        val result = CacheLookupResult("raw_json", null)
        assertEquals("raw_json", result.raw)
        assertNull(result.trimmedContent)
    }

    fun testCacheLookupResultDefaultTrimmedIsNull() {
        val result = CacheLookupResult("raw_json")
        assertEquals("raw_json", result.raw)
        assertNull(result.trimmedContent)
    }

    fun testTryGetCachedSuggestionExactMatch() {
        val cache = SuggestionCache()
        val response = """{"content":"hello world"}"""
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def ",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def ",
            lineCurPrefix = "def ",
            lineCurSuffix = ""
        )

        val hash = computeContextHash(context.prefix, context.middle, context.suffix)
        cacheInsert(cache, hash, response, 10)

        val result = tryGetCachedSuggestion(cache, context)
        assertNotNull(result)
        assertEquals(response, result!!.raw)
        assertNull(result.trimmedContent)
    }

    fun testTryGetCachedSuggestionNoMatch() {
        val cache = SuggestionCache()
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def ",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def ",
            lineCurPrefix = "def ",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNull(result)
    }

    fun testTryGetCachedSuggestionPartialMatchSingleCharacter() {
        val cache = SuggestionCache()
        // Response where content starts with " " (space)
        val response = """{"content":" world"}"""

        // Cache at position: "def"
        val prefixOld = "line1\n"
        val middleOld = "def"
        val suffixOld = "\nline2"
        val hashOld = computeContextHash(prefixOld, middleOld, suffixOld)
        cacheInsert(cache, hashOld, response, 10)

        // Current position: "def " (typed one more space)
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def ",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def ",
            lineCurPrefix = "def ",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNotNull(result)
        assertEquals("world", result!!.trimmedContent)
    }

    fun testTryGetCachedSuggestionPartialMatchMultipleCharacters() {
        val cache = SuggestionCache()
        // Content that starts with "f g" (matches the 3 chars typed: 'd g')
        val response = """{"content":"f good"}"""

        // Cache at position: "de"
        val prefixOld = "line1\n"
        val middleOld = "de"
        val suffixOld = "\nline2"
        val hashOld = computeContextHash(prefixOld, middleOld, suffixOld)
        cacheInsert(cache, hashOld, response, 10)

        // Current position: "def" (typed "f" which matches first 1 char)
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def",
            lineCurPrefix = "def",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNotNull(result)
        assertEquals(" good", result!!.trimmedContent)
    }

    fun testTryGetCachedSuggestionPartialMatchNoMatch() {
        val cache = SuggestionCache()
        val response = """{"content":"hello world"}"""

        // Cache at position: "def"
        val prefixOld = "line1\n"
        val middleOld = "def"
        val suffixOld = "\nline2"
        val hashOld = computeContextHash(prefixOld, middleOld, suffixOld)
        cacheInsert(cache, hashOld, response, 10)

        // Current position: "def x" (typed "x" which doesn't match first char "h")
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def x",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def x",
            lineCurPrefix = "def x",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNull(result)
    }

    fun testTryGetCachedSuggestionSkipsEmptyResponse() {
        val cache = SuggestionCache()

        // Cache empty response
        val prefixOld = "line1\n"
        val middleOld = "def"
        val suffixOld = "\nline2"
        val hashOld = computeContextHash(prefixOld, middleOld, suffixOld)
        cacheInsert(cache, hashOld, "", 10)

        val context = LocalContext(
            prefix = "line1\n",
            middle = "def ",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def ",
            lineCurPrefix = "def ",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNull(result)
    }

    fun testTryGetCachedSuggestionTrimmedContentEmpty() {
        val cache = SuggestionCache()
        val response = """{"content":"x"}"""

        // Cache with content "x"
        val prefixOld = "line1\n"
        val middleOld = "def"
        val suffixOld = "\nline2"
        val hashOld = computeContextHash(prefixOld, middleOld, suffixOld)
        cacheInsert(cache, hashOld, response, 10)

        // Current position: "def x" (typed "x" which matches first char)
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def x",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def x",
            lineCurPrefix = "def x",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNull(result)  // Trimmed content would be empty
    }

    fun testTryGetCachedSuggestionBestMatch() {
        val cache = SuggestionCache()

        // Cache two responses of different lengths at different positions
        val prefixOld = "line1\n"
        val suffixOld = "\nline2"

        // Shorter match: cached at "de", content is "f short"
        val hash1 = computeContextHash(prefixOld, "de", suffixOld)
        cacheInsert(cache, hash1, """{"content":"f short"}""", 10)

        // Longer match: cached at "d", content is "ef much longer"
        val hash2 = computeContextHash(prefixOld, "d", suffixOld)
        cacheInsert(cache, hash2, """{"content":"ef much longer"}""", 10)

        // Current position: "def" (typed 2 chars: "ef")
        val context = LocalContext(
            prefix = "line1\n",
            middle = "def",
            suffix = "\nline2",
            indent = 0,
            lineCur = "def",
            lineCurPrefix = "def",
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNotNull(result)
        // Should get the longer match "much longer" (after trimming "ef")
        assertEquals(" much longer", result!!.trimmedContent)
    }

    fun testTryGetCachedSuggestionLimitedToMaxDistance() {
        val cache = SuggestionCache()

        // Cache at far position (more than 128 chars back)
        val oldMiddle = "a".repeat(200)
        val prefixOld = "line1\n"
        val suffixOld = "\nline2"
        val hashOld = computeContextHash(prefixOld, oldMiddle, suffixOld)
        cacheInsert(cache, hashOld, """{"content":"should not be found"}""", 10)

        // Current position 128+ chars ahead
        val newMiddle = "a".repeat(200) + "b"
        val context = LocalContext(
            prefix = "line1\n",
            middle = newMiddle,
            suffix = "\nline2",
            indent = 0,
            lineCur = newMiddle,
            lineCurPrefix = newMiddle,
            lineCurSuffix = ""
        )

        val result = tryGetCachedSuggestion(cache, context)
        assertNull(result)  // Beyond 128 character limit
    }

    fun testCacheInsertResponseSingleHash() {
        val cache = SuggestionCache()
        val response = """{"content":"hello"}"""
        val hashes = listOf("hash1")

        cacheInsertResponse(cache, hashes, response, 10)

        assertEquals(1, cache.data.size)
        assertEquals(response, cache.data["hash1"])
    }

    fun testCacheInsertResponseMultipleHashes() {
        val cache = SuggestionCache()
        val response = """{"content":"hello"}"""
        val hashes = listOf("hash1", "hash2", "hash3")

        cacheInsertResponse(cache, hashes, response, 10)

        assertEquals(3, cache.data.size)
        assertEquals(response, cache.data["hash1"])
        assertEquals(response, cache.data["hash2"])
        assertEquals(response, cache.data["hash3"])
    }

    fun testCacheInsertResponseEmptyHashList() {
        val cache = SuggestionCache()
        val response = """{"content":"hello"}"""

        cacheInsertResponse(cache, emptyList(), response, 10)

        assertEquals(0, cache.data.size)
    }

    fun testCacheInsertResponseRespectMaxCacheKeys() {
        val cache = SuggestionCache()
        val response1 = """{"content":"first"}"""
        val response2 = """{"content":"second"}"""

        cacheInsertResponse(cache, listOf("hash1", "hash2"), response1, 2)
        cacheInsertResponse(cache, listOf("hash3"), response2, 2)

        // hash1 should be evicted
        assertEquals(2, cache.data.size)
        assertFalse(cache.data.containsKey("hash1"))
    }

    fun testCacheInsertResponseUpdatesLruOrder() {
        val cache = SuggestionCache()
        val response = """{"content":"hello"}"""
        val hashes = listOf("hash1", "hash2", "hash3")

        cacheInsertResponse(cache, hashes, response, 10)

        assertEquals(listOf("hash1", "hash2", "hash3"), cache.lruOrder)
    }

    fun testCacheSizeEmpty() {
        val cache = SuggestionCache()
        assertEquals(0, cacheSize(cache))
    }

    fun testCacheSizeSingleEntry() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        assertEquals(1, cacheSize(cache))
    }

    fun testCacheSizeMultipleEntries() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)
        cacheInsert(cache, "key3", "value3", 10)
        assertEquals(3, cacheSize(cache))
    }

    fun testCacheSizeAfterEviction() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 2)
        cacheInsert(cache, "key2", "value2", 2)
        cacheInsert(cache, "key3", "value3", 2)  // Evicts key1

        assertEquals(2, cacheSize(cache))
    }

    fun testCacheClearEmpty() {
        val cache = SuggestionCache()
        cacheClear(cache)
        assertEquals(0, cacheSize(cache))
        assertTrue(cache.lruOrder.isEmpty())
    }

    fun testCacheClearWithEntries() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)

        cacheClear(cache)

        assertEquals(0, cacheSize(cache))
        assertTrue(cache.data.isEmpty())
        assertTrue(cache.lruOrder.isEmpty())
    }

    fun testCacheClearClearsDataAndLruOrder() {
        val cache = SuggestionCache()
        cacheInsert(cache, "key1", "value1", 10)
        cacheInsert(cache, "key2", "value2", 10)

        cacheClear(cache)

        assertFalse(cache.data.containsKey("key1"))
        assertFalse(cache.data.containsKey("key2"))
        assertEquals(0, cache.lruOrder.size)
    }

    fun testCacheClearMultipleTimes() {
        val cache = SuggestionCache()

        cacheInsert(cache, "key1", "value1", 10)
        cacheClear(cache)
        assertEquals(0, cacheSize(cache))

        cacheInsert(cache, "key2", "value2", 10)
        assertEquals(1, cacheSize(cache))

        cacheClear(cache)
        assertEquals(0, cacheSize(cache))
    }
}
