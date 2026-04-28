package com.github.lucatume.completamente.uitest.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the fake-agent.sh stub on the test classpath and builds the
 * `order89CliCommand` setting value that points at it.
 */
class FakeAgentCli private constructor(val scriptPath: Path) {

    fun buildOrder89Command(fixture: String): String =
        "${shellQuote(scriptPath.toString())} --fixture ${shellQuote(fixture)} \"%%prompt_file%%\""

    private fun shellQuote(s: String): String =
        if (s.contains(' ') || s.contains('"')) "\"${s.replace("\"", "\\\"")}\"" else s

    companion object {
        fun locate(): FakeAgentCli {
            val url = FakeAgentCli::class.java.classLoader.getResource("fake-agent/fake-agent.sh")
                ?: error("fake-agent/fake-agent.sh not found on classpath")
            val path = Paths.get(url.toURI())
            require(Files.isExecutable(path)) {
                "fake-agent.sh must be executable; ran chmod +x in plan task 4.3?"
            }
            return FakeAgentCli(path)
        }
    }
}
