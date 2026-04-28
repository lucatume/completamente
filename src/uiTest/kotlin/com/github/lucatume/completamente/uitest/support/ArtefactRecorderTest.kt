package com.github.lucatume.completamente.uitest.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ArtefactRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun testCapturesScreenshotHierarchyLogTailAndSummaryOnFailure() {
        val log = tmp.newFile("idea.log").also {
            Files.writeString(it.toPath(), (1..1000).joinToString("\n") { i -> "line-$i" })
        }
        val recorder = ArtefactRecorder(
            reportsDir = tmp.newFolder("reports").toPath(),
            screenshotProvider = { ByteArray(8) { 0x42 } },
            hierarchyProvider = { "<html>tree</html>" },
            ideaLogPath = log.toPath(),
            logTailLines = 500,
        )

        val outDir = recorder.captureFailure("FooUiTest", "testBar", AssertionError("boom"))

        assertTrue(outDir.resolve("screenshot.png").toFile().exists())
        assertEquals(8, outDir.resolve("screenshot.png").toFile().length())
        assertTrue(outDir.resolve("hierarchy.html").toFile().readText().contains("tree"))
        val tail = outDir.resolve("idea.log.tail").toFile().readText().lines().filter { it.isNotEmpty() }
        assertEquals(500, tail.size)
        assertEquals("line-501", tail.first())
        assertEquals("line-1000", tail.last())
    }

    @Test fun testRecordEventsAppendsJsonLines() {
        val recorder = ArtefactRecorder(
            reportsDir = tmp.newFolder("reports").toPath(),
            screenshotProvider = { ByteArray(0) },
            hierarchyProvider = { "" },
            ideaLogPath = null,
        )
        recorder.recordEvent("FooUiTest", "testBar", "click", mapOf("xpath" to "//button"))
        recorder.recordEvent("FooUiTest", "testBar", "type", mapOf("text" to "hi"))
        val events = recorder.eventsFile("FooUiTest", "testBar").toFile().readLines()
        assertEquals(2, events.size)
        assertTrue(events[0].contains("\"action\":\"click\""))
        assertTrue(events[1].contains("\"text\":\"hi\""))
    }
}
