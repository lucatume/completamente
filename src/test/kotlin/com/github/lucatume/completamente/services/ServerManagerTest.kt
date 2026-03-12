package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import java.io.File

class ServerManagerTest : BaseCompletionTest() {

    // --- parseLogFileFromArgs tests ---

    fun testParseLogFileFromArgsEqualsForm() {
        val cmdLine = "llama-server --log-file=/tmp/server.log --port 8080"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/tmp/server.log"), result)
    }

    fun testParseLogFileFromArgsSpaceSeparatedForm() {
        val cmdLine = "llama-server --log-file /tmp/server.log --port 8080"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/tmp/server.log"), result)
    }

    fun testParseLogFileFromArgsNoFlag() {
        val cmdLine = "llama-server --port 8080 --host 127.0.0.1"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertNull(result)
    }

    fun testParseLogFileFromArgsEmptyString() {
        val result = ServerManager.parseLogFileFromArgs("")
        assertNull(result)
    }

    fun testParseLogFileFromArgsComplexCommandLine() {
        val cmdLine = "/usr/local/bin/llama-server -m /models/model.gguf --host 127.0.0.1 --port 8017 --log-file=/var/log/llama/server.log --n-gpu-layers 35 --ctx-size 4096"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/var/log/llama/server.log"), result)
    }

    fun testParseLogFileFromArgsLogFileAtEnd() {
        val cmdLine = "llama-server --port 8080 --log-file /home/user/logs/llama.log"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/home/user/logs/llama.log"), result)
    }

    fun testParseLogFileFromArgsLogFileAtStart() {
        val cmdLine = "llama-server --log-file=/opt/logs/server.log --port 8080"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/opt/logs/server.log"), result)
    }

    fun testParseLogFileFromArgsPathWithHyphens() {
        val cmdLine = "llama-server --log-file=/var/log/my-app/server-output.log --port 8080"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/var/log/my-app/server-output.log"), result)
    }

    fun testParseLogFileFromArgsPathWithDots() {
        val cmdLine = "llama-server --log-file /home/user/.config/llama/server.2024.log"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/home/user/.config/llama/server.2024.log"), result)
    }

    fun testParseLogFileFromArgsRelativePath() {
        val cmdLine = "llama-server --log-file=logs/server.log"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("logs/server.log"), result)
    }

    fun testParseLogFileFromArgsDoubleQuotedPathEquals() {
        val cmdLine = """llama-server --log-file="/path with spaces/server.log" --port 8080"""
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/path with spaces/server.log"), result)
    }

    fun testParseLogFileFromArgsSingleQuotedPathEquals() {
        val cmdLine = "llama-server --log-file='/path with spaces/server.log' --port 8080"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/path with spaces/server.log"), result)
    }

    fun testParseLogFileFromArgsDoubleQuotedPathSpace() {
        val cmdLine = """llama-server --log-file "/path with spaces/server.log" --port 8080"""
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/path with spaces/server.log"), result)
    }

    fun testParseLogFileFromArgsSingleQuotedPathSpace() {
        val cmdLine = "llama-server --log-file '/path with spaces/server.log' --port 8080"
        val result = ServerManager.parseLogFileFromArgs(cmdLine)
        assertEquals(File("/path with spaces/server.log"), result)
    }

    // --- isLocalUrl tests ---

    fun testIsLocalUrlWithLocalhost() {
        assertTrue(ServerManager.isLocalUrl("http://localhost:8080"))
    }

    fun testIsLocalUrlWith127001() {
        assertTrue(ServerManager.isLocalUrl("http://127.0.0.1:8017"))
    }

    fun testIsLocalUrlWith127Prefix() {
        assertTrue(ServerManager.isLocalUrl("http://127.0.0.2:8017"))
    }

    fun testIsLocalUrlWithRemoteHost() {
        assertFalse(ServerManager.isLocalUrl("http://example.com:8080"))
    }

    fun testIsLocalUrlWithIpv6Loopback() {
        assertTrue(ServerManager.isLocalUrl("http://[::1]:8017"))
    }

    fun testIsLocalUrlWithInvalidUrl() {
        assertFalse(ServerManager.isLocalUrl("not a url"))
    }

    fun testIsLocalUrlWithEmptyString() {
        assertFalse(ServerManager.isLocalUrl(""))
    }

    // --- tokenizeCommand tests ---

    fun testTokenizeCommandSimple() {
        val tokens = ServerManager.tokenizeCommand("llama-server --port 8080")
        assertEquals(listOf("llama-server", "--port", "8080"), tokens)
    }

    fun testTokenizeCommandWithQuotedSegment() {
        val tokens = ServerManager.tokenizeCommand("""llama-server --model "/path with spaces/model.gguf" --port 8080""")
        assertEquals(listOf("llama-server", "--model", "/path with spaces/model.gguf", "--port", "8080"), tokens)
    }

    fun testTokenizeCommandEmptyString() {
        val tokens = ServerManager.tokenizeCommand("")
        assertEquals(emptyList<String>(), tokens)
    }

    // --- extractHostPort tests ---

    fun testExtractHostPortDefault() {
        val (host, port) = ServerManager.extractHostPort("http://127.0.0.1:8017")
        assertEquals("127.0.0.1", host)
        assertEquals(8017, port)
    }

    fun testExtractHostPortLocalhostConverted() {
        val (host, port) = ServerManager.extractHostPort("http://localhost:9090")
        assertEquals("127.0.0.1", host)
        assertEquals(9090, port)
    }

    fun testExtractHostPortMissingPortUsesDefault() {
        val (host, port) = ServerManager.extractHostPort("http://127.0.0.1")
        assertEquals("127.0.0.1", host)
        assertEquals(8017, port)
    }

    fun testExtractHostPortInvalidUrlReturnsDefaults() {
        val (host, port) = ServerManager.extractHostPort("not a url")
        assertEquals("127.0.0.1", host)
        assertEquals(8017, port)
    }

    fun testTokenizeCommandSingleQuotesNotHandled() {
        // tokenizeCommand only handles double-quoted segments; single quotes are treated as part of tokens
        val tokens = ServerManager.tokenizeCommand("llama-server --model '/path' --port 8080")
        assertEquals(listOf("llama-server", "--model", "'/path'", "--port", "8080"), tokens)
    }

    fun testTokenizeCommandConsecutiveSpaces() {
        val tokens = ServerManager.tokenizeCommand("llama-server   --port   8080")
        assertEquals(listOf("llama-server", "--port", "8080"), tokens)
    }

    fun testTokenizeCommandWhitespaceOnly() {
        val tokens = ServerManager.tokenizeCommand("   ")
        assertEquals(emptyList<String>(), tokens)
    }

    fun testExtractHostPortEmptyString() {
        val (host, port) = ServerManager.extractHostPort("")
        assertEquals("127.0.0.1", host)
        assertEquals(8017, port)
    }

    fun testParseLogFileFromArgsDanglingEquals() {
        val result = ServerManager.parseLogFileFromArgs("llama-server --log-file= --port 8080")
        assertNull(result)
    }

    fun testParseLogFileFromArgsFlagAtEndNoValue() {
        val result = ServerManager.parseLogFileFromArgs("llama-server --log-file")
        assertNull(result)
    }
}
