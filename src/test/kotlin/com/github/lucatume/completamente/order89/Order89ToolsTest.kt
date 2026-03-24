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

    // -- stripHtml tests --

    fun testStripHtmlRemovesScriptBlocks() {
        val html = "Hello<script type=\"text/javascript\">var x = 1;</script> World"
        assertEquals("Hello World", stripHtml(html))
    }

    fun testStripHtmlRemovesStyleBlocks() {
        val html = "Hello<style>.cls { color: red; }</style> World"
        assertEquals("Hello World", stripHtml(html))
    }

    fun testStripHtmlReplacesBlockTagsWithNewlines() {
        val html = "<p>First</p><p>Second</p>"
        val result = stripHtml(html)
        assertTrue(result.contains("First"))
        assertTrue(result.contains("Second"))
        assertTrue(result.contains("\n"))
    }

    fun testStripHtmlDecodesCommonEntities() {
        val html = "A &amp; B &lt; C &gt; D &quot;E&quot; F&apos;s &nbsp; G &mdash; H &ndash; I &#39;J&#x27;"
        val result = stripHtml(html)
        assertTrue(result.contains("A & B"))
        assertTrue(result.contains("< C >"))
        assertTrue(result.contains("\"E\""))
        assertTrue(result.contains("F's"))
        assertTrue(result.contains("\u2014"))
        assertTrue(result.contains("\u2013"))
        assertTrue(result.contains("'J'"))
    }

    fun testStripHtmlDecodesNumericEntities() {
        val html = "&#65;&#66;&#67;"
        assertEquals("ABC", stripHtml(html))
    }

    fun testStripHtmlCollapsesWhitespace() {
        val html = "Hello    World\n\n\n\n\nEnd"
        val result = stripHtml(html)
        assertFalse(result.contains("    "))
        assertFalse(result.contains("\n\n\n"))
    }

    fun testStripHtmlPlainTextPassthrough() {
        val plain = "Just some plain text with no HTML."
        assertEquals("Just some plain text with no HTML.", stripHtml(plain))
    }

    fun testStripHtmlEmptyString() {
        assertEquals("", stripHtml(""))
    }

    fun testStripHtmlNavFilterRemovesShortNavLines() {
        val html = """
            <nav>
            <a href="/">Home</a>
            <a href="/about">About</a>
            <a href="/contact">Contact</a>
            <a href="/help">Help</a>
            </nav>
            <div>This is the actual content of the page with many words.</div>
        """.trimIndent()
        val result = stripHtml(html)
        assertTrue(result.contains("This is the actual content"))
        assertFalse(result.contains("Home") && result.contains("About") && result.contains("Contact") && result.contains("Help"))
    }

    fun testStripHtmlNestedTags() {
        val html = "<p><b>bold</b> text</p>"
        assertEquals("bold text", stripHtml(html))
    }

    fun testStripHtmlMalformedUnclosedTags() {
        val html = "<p>unclosed <b>bold"
        assertEquals("unclosed bold", stripHtml(html))
    }

    fun testStripHtmlDecodesNonAsciiNumericEntities() {
        val html = "it&#8217;s a test"
        val result = stripHtml(html)
        assertTrue(result.contains("\u2019"))
        assertTrue(result.contains("s a test"))
    }

    // -- truncateOnWordBoundary tests --

    fun testTruncateOnWordBoundaryExactLimit() {
        val text = "Hello World"
        assertEquals("Hello World", truncateOnWordBoundary(text, 11))
    }

    fun testTruncateOnWordBoundaryShortTextUnchanged() {
        val text = "Hello World"
        assertEquals("Hello World", truncateOnWordBoundary(text, 100))
    }

    fun testTruncateOnWordBoundaryBreaksAtSpace() {
        val text = "Hello World Foo Bar"
        val result = truncateOnWordBoundary(text, 11)
        assertEquals("Hello World [...]", result)
    }

    fun testTruncateOnWordBoundaryNoSpaces() {
        val text = "abcdefghijklmnop"
        val result = truncateOnWordBoundary(text, 10)
        assertEquals("abcdefghij [...]", result)
    }

    fun testTruncateOnWordBoundaryEmptyString() {
        assertEquals("", truncateOnWordBoundary("", 100))
    }

    fun testTruncateOnWordBoundaryCustomLimit() {
        val text = "one two three four five six"
        val result = truncateOnWordBoundary(text, 15)
        assertEquals("one two three [...]", result)
    }

    // -- resolveDocsets tests --

    fun testResolveDocsetsNullReturnsAllDefaults() {
        val result = resolveDocsets(null)
        assertEquals(listOf("php", "wordpress", "laravel", "react", "javascript", "nodejs"), result)
    }

    fun testResolveDocsetsBlankReturnsAllDefaults() {
        val result = resolveDocsets("  ")
        assertEquals(listOf("php", "wordpress", "laravel", "react", "javascript", "nodejs"), result)
    }

    fun testResolveDocsetsSingleAlias() {
        val result = resolveDocsets("wp")
        assertEquals(listOf("wordpress"), result)
    }

    fun testResolveDocsetsTypescriptExpandsToReactAndNodejs() {
        val result = resolveDocsets("ts")
        assertEquals(listOf("react", "nodejs"), result)
    }

    fun testResolveDocsetsKotlinMapsToUserContrib() {
        val result = resolveDocsets("kt")
        assertEquals(listOf("usercontribKotlin"), result)
    }

    fun testResolveDocsetsCommaSeparated() {
        val result = resolveDocsets("php,wordpress")
        assertEquals(listOf("php", "wordpress"), result)
    }

    fun testResolveDocsetsDeduplicates() {
        val result = resolveDocsets("ts,react")
        assertEquals(listOf("react", "nodejs"), result)
    }

    fun testResolveDocsetsCaseInsensitive() {
        val result = resolveDocsets("PHP,WordPress")
        assertEquals(listOf("php", "wordpress"), result)
    }

    fun testResolveDocsetsUnknownPassedThrough() {
        val result = resolveDocsets("ruby")
        assertEquals(listOf("ruby"), result)
    }

    fun testResolveDocsetsWhitespaceAroundAliases() {
        val result = resolveDocsets(" php , wp ")
        assertEquals(listOf("php", "wordpress"), result)
    }

    // -- formatDocResults tests --

    fun testFormatDocResultsEmptyList() {
        assertEquals("No documentation found.", formatDocResults(emptyList(), emptyMap()))
    }

    fun testFormatDocResultsSingleResult() {
        val results = listOf(DashDocResult("add_action", "Function", "WordPress", "http://x"))
        val content = mapOf("http://x" to "<p>Adds a callback function to an action hook.</p>")
        val output = formatDocResults(results, content)
        assertTrue(output.startsWith("[WordPress] add_action (Function)"))
        assertTrue(output.contains("Adds a callback function to an action hook."))
    }

    fun testFormatDocResultsMultipleResults() {
        val results = listOf(
            DashDocResult("foo", "Function", "PHP", "http://a"),
            DashDocResult("bar", "Class", "Laravel", "http://b")
        )
        val content = mapOf("http://a" to "<p>Foo docs</p>", "http://b" to "<p>Bar docs</p>")
        val output = formatDocResults(results, content)
        assertTrue(output.contains("[PHP] foo (Function)"))
        assertTrue(output.contains("[Laravel] bar (Class)"))
        assertTrue(output.contains("\n\n---\n\n"))
    }

    fun testFormatDocResultsContentUnavailable() {
        val results = listOf(DashDocResult("missing", "Function", "PHP", "http://missing"))
        val output = formatDocResults(results, emptyMap())
        assertTrue(output.contains("(content unavailable)"))
    }

    fun testFormatDocResultsTruncatesLongContent() {
        val results = listOf(DashDocResult("big", "Guide", "React", "http://big"))
        val longHtml = "<p>${"word ".repeat(1000)}</p>"
        val content = mapOf("http://big" to longHtml)
        val output = formatDocResults(results, content, maxCharsPerResult = 100)
        assertTrue(output.contains("[...]"))
    }

    fun testFormatDocResultsStripsHtmlFromContent() {
        val results = listOf(DashDocResult("test", "Function", "PHP", "http://test"))
        val content = mapOf("http://test" to "<script>evil()</script><p>Clean content here.</p>")
        val output = formatDocResults(results, content)
        assertTrue(output.contains("Clean content here."))
        assertFalse(output.contains("evil()"))
        assertFalse(output.contains("<script>"))
    }
}
