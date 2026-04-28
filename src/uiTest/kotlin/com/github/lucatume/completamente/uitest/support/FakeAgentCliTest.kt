package com.github.lucatume.completamente.uitest.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class FakeAgentCliTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun testScriptIsResolvableAndExecutable() {
        val cli = FakeAgentCli.locate()
        assertTrue("fake-agent.sh must exist on classpath", Files.exists(cli.scriptPath))
        assertTrue("fake-agent.sh must be executable", Files.isExecutable(cli.scriptPath))
    }

    @Test fun testBuildOrder89CommandContainsFixtureAndPlaceholder() {
        val cli = FakeAgentCli.locate()
        val cmd = cli.buildOrder89Command(fixture = "rename-variable.txt")
        assertTrue(cmd.contains("fake-agent.sh"))
        assertTrue(cmd.contains("rename-variable.txt"))
        assertTrue(cmd.contains("%%prompt_file%%"))
    }

    @Test fun testRunningTheScriptEmitsFixtureContents() {
        val cli = FakeAgentCli.locate()
        val promptFile = tmp.newFile("prompt.txt").also {
            Files.writeString(it.toPath(), "ignored prompt")
        }
        val process = ProcessBuilder(
            cli.scriptPath.toString(),
            "--fixture", "rename-variable.txt",
            promptFile.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(0, exit)
        assertTrue("output must contain canned fixture content", output.contains("renamedVariable"))
    }
}
