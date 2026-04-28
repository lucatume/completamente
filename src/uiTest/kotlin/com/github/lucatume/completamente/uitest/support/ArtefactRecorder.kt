package com.github.lucatume.completamente.uitest.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Writes per-test debug artefacts under [reportsDir]/<className>/<methodName>/.
 * Pure I/O — no Robot dependency so it stays unit-testable.
 */
class ArtefactRecorder(
    private val reportsDir: Path,
    private val screenshotProvider: () -> ByteArray,
    private val hierarchyProvider: () -> String,
    private val ideaLogPath: Path? = null,
    private val logTailLines: Int = 500,
) {
    fun captureFailure(className: String, methodName: String, failure: Throwable): Path {
        val dir = ensureDir(className, methodName)
        runCatching { Files.write(dir.resolve("screenshot.png"), screenshotProvider()) }
        runCatching { Files.writeString(dir.resolve("hierarchy.html"), hierarchyProvider()) }
        ideaLogPath?.let { writeLogTail(it, dir.resolve("idea.log.tail")) }
        Files.writeString(
            dir.resolve("failure.txt"),
            buildString {
                append(failure::class.qualifiedName).append(": ").append(failure.message).append("\n\n")
                append(failure.stackTraceToString())
            },
        )
        return dir
    }

    fun eventsFile(className: String, methodName: String): Path =
        ensureDir(className, methodName).resolve("events.jsonl")

    fun recordEvent(className: String, methodName: String, action: String, fields: Map<String, String>) {
        val file = eventsFile(className, methodName)
        val payload = buildString {
            append("{\"action\":\"").append(action).append("\"")
            fields.forEach { (k, v) ->
                append(",\"").append(k).append("\":\"").append(escape(v)).append("\"")
            }
            append("}\n")
        }
        Files.writeString(file, payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun ensureDir(className: String, methodName: String): Path {
        val dir = reportsDir.resolve(className).resolve(methodName)
        Files.createDirectories(dir)
        return dir
    }

    private fun writeLogTail(src: Path, dst: Path) {
        runCatching {
            val lines = Files.readAllLines(src)
            val tail = if (lines.size <= logTailLines) lines else lines.subList(lines.size - logTailLines, lines.size)
            Files.writeString(dst, tail.joinToString("\n"))
        }
    }

    private fun escape(s: String) = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")
}
