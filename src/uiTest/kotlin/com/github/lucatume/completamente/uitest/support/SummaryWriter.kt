package com.github.lucatume.completamente.uitest.support

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes build/reports/uiTest/summary.json — the primary debug surface for an agent.
 *
 * Driven from [com.github.lucatume.completamente.uitest.BaseCompletamenteUiTest] via the
 * standard JUnit 4 [org.junit.rules.TestWatcher] callbacks (succeeded / failed / skipped /
 * starting / finished). Registering a JUnit 4 RunListener through Gradle's Test task
 * needs a custom runner; this stays simpler.
 */
object SummaryWriter {

    private data class Record(
        var status: String = "passed",
        var failure: String? = null,
        val started: Long = System.currentTimeMillis(),
        var finished: Long = 0L,
    )

    private val records = ConcurrentHashMap<String, Record>()
    private val runStarted = Instant.now()

    fun started(className: String, methodName: String) {
        records[key(className, methodName)] = Record()
    }

    fun passed(className: String, methodName: String) {
        records[key(className, methodName)]?.let {
            it.status = "passed"
            it.finished = System.currentTimeMillis()
        }
        flush()
    }

    fun failed(className: String, methodName: String, message: String?) {
        records[key(className, methodName)]?.let {
            it.status = "failed"
            it.failure = message
            it.finished = System.currentTimeMillis()
        }
        flush()
    }

    fun ignored(className: String, methodName: String) {
        records[key(className, methodName)] = Record(
            status = "ignored",
            finished = System.currentTimeMillis(),
        )
        flush()
    }

    private fun flush() {
        val reportsDir = Path.of(System.getProperty("ui.test.reports.dir", "build/reports/uiTest"))
        Files.createDirectories(reportsDir)
        val out = reportsDir.resolve("summary.json")
        val tests = records.entries.sortedBy { it.key }.joinToString(",\n    ") { (k, v) ->
            val (cls, mtd) = k.split('#')
            val duration = (if (v.finished == 0L) System.currentTimeMillis() else v.finished) - v.started
            buildString {
                append("{\"class\":\"").append(cls).append("\",")
                append("\"method\":\"").append(mtd).append("\",")
                append("\"status\":\"").append(v.status).append("\",")
                append("\"durationMs\":").append(duration)
                if (v.failure != null) {
                    append(",\"failure\":\"").append(escape(v.failure!!)).append("\"")
                    append(",\"artefacts\":\"").append(reportsDir.resolve(cls).resolve(mtd)).append("\"")
                }
                append("}")
            }
        }
        val body = """
            {
              "started": "$runStarted",
              "tests": [
                $tests
              ]
            }
        """.trimIndent()
        Files.writeString(out, body)
    }

    private fun key(cls: String, mtd: String) = "$cls#$mtd"
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")
}
