package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest

class Order89ToolsTest : BaseCompletionTest() {

    // -- searchFileContent tests --

    fun testSearchFileContentFindsMatches() {
        val text = "line one\nline two\nline three"
        val results = searchFileContent(text, "two")
        assertEquals(1, results.size)
        assertEquals(2, results[0].first)
        assertEquals("line two", results[0].second)
    }

    fun testSearchFileContentCaseInsensitiveByDefault() {
        val text = "Hello World\nhello world\nHELLO WORLD"
        val results = searchFileContent(text, "hello")
        assertEquals(3, results.size)
    }

    fun testSearchFileContentCaseSensitive() {
        val text = "Hello World\nhello world\nHELLO WORLD"
        val results = searchFileContent(text, "hello", caseSensitive = true)
        assertEquals(1, results.size)
        assertEquals(2, results[0].first)
    }

    fun testSearchFileContentNoMatches() {
        val text = "line one\nline two"
        val results = searchFileContent(text, "xyz")
        assertTrue(results.isEmpty())
    }

    fun testSearchFileContentEmptyFile() {
        val results = searchFileContent("", "test")
        assertTrue(results.isEmpty())
    }

    fun testSearchFileContentMultipleMatchesSameLine() {
        val text = "foo bar foo"
        val results = searchFileContent(text, "foo")
        assertEquals(1, results.size)
        assertEquals(1, results[0].first)
    }

    // -- formatSearchResults tests --

    fun testFormatSearchResultsEmpty() {
        assertEquals("No matches found.", formatSearchResults(emptyList()))
    }

    fun testFormatSearchResultsSingle() {
        val matches = listOf(FileSearchMatch("src/Foo.kt", 10, "fun foo()"))
        val result = formatSearchResults(matches)
        assertEquals("src/Foo.kt:10: fun foo()", result)
    }

    fun testFormatSearchResultsMultiple() {
        val matches = listOf(
            FileSearchMatch("src/Foo.kt", 10, "fun foo()"),
            FileSearchMatch("src/Bar.kt", 5, "fun bar()")
        )
        val result = formatSearchResults(matches)
        assertEquals("src/Foo.kt:10: fun foo()\nsrc/Bar.kt:5: fun bar()", result)
    }

    fun testFormatSearchResultsCappedAtMax() {
        val matches = (1..25).map { FileSearchMatch("file.kt", it, "line $it") }
        val result = formatSearchResults(matches)
        assertEquals(20, result.split("\n").size)
    }

    fun testFormatSearchResultsCustomMax() {
        val matches = (1..10).map { FileSearchMatch("file.kt", it, "line $it") }
        val result = formatSearchResults(matches, maxResults = 3)
        assertEquals(3, result.split("\n").size)
    }

    // -- FileSearch platform tests --

    fun testFileSearchFindsMatchInProjectFile() {
        myFixture.addFileToProject("src/Hello.kt", "fun hello() {\n    println(\"world\")\n}")
        val result = FileSearchTool.execute(project, "hello")
        assertTrue(result.contains("Hello.kt"))
        assertTrue(result.contains("fun hello()"))
    }

    fun testFileSearchCaseInsensitiveByDefault() {
        myFixture.addFileToProject("src/Greet.kt", "fun sayHello() {}")
        val result = FileSearchTool.execute(project, "sayhello")
        assertTrue(result.contains("Greet.kt"))
    }

    fun testFileSearchCaseSensitiveRespectsFlag() {
        myFixture.addFileToProject("src/Case.kt", "fun sayHello() {}\nfun sayhello() {}")
        val result = FileSearchTool.execute(project, "sayHello", caseSensitive = true)
        assertTrue(result.contains("sayHello"))
        assertFalse(result.contains("sayhello()"))
    }

    fun testFileSearchNoMatchesReturnsMessage() {
        myFixture.addFileToProject("src/Empty.kt", "fun nothing() {}")
        val result = FileSearchTool.execute(project, "nonexistent_identifier_xyz")
        assertEquals("No matches found.", result)
    }

    fun testFileSearchFindsMultipleFilesWithMatches() {
        myFixture.addFileToProject("main/Foo.kt", "fun target() {}")
        myFixture.addFileToProject("other/Bar.kt", "fun target() {}")
        val result = FileSearchTool.execute(project, "target")
        assertTrue(result.contains("Foo.kt"))
        assertTrue(result.contains("Bar.kt"))
    }

    // -- WebSearch test --

    fun testWebSearchReturnsNotImplemented() {
        val result = WebSearchTool.execute("kotlin coroutines")
        assertEquals("WebSearch is not yet implemented.", result)
    }
}
