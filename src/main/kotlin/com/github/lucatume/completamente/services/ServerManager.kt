package com.github.lucatume.completamente.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.PathManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class ServerManager : Disposable {

    enum class ServerState { UNKNOWN, RUNNING, STOPPED, STARTING, ERROR }
    enum class OwnershipState { UNMANAGED, MANAGED }

    @Volatile
    var serverState: ServerState = ServerState.UNKNOWN
        private set

    @Volatile
    var ownershipState: OwnershipState = OwnershipState.UNMANAGED
        private set

    @Volatile
    var serverLogFile: File? = null
        private set

    /** Set to true when the user declines the "Start Server" notification. Resets on IDE restart. */
    @Volatile
    var userDeclinedServerStart: Boolean = false

    private var managedProcess: Process? = null
    private var adoptedPid: Long? = null
    private var outputReaderThread: Thread? = null
    private val recentOutput = java.util.concurrent.LinkedBlockingDeque<String>(10)
    private val serverLock = Any()
    private val logger = Logger.getInstance(ServerManager::class.java)
    private val shutdownHook = Thread({ stopServer() }, "completamente-shutdown-hook")

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /** Returns the last 10 lines of server output. */
    fun getRecentOutput(): List<String> = recentOutput.toList()

    /** Probe the server without mutating state. */
    private fun probeServerHealth(): ServerState {
        val settings = SettingsState.getInstance()
        val url = settings.serverUrl
        return try {
            val connection = URI("$url/health").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                val code = connection.responseCode
                if (code == 200) ServerState.RUNNING else ServerState.STOPPED
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            ServerState.STOPPED
        }
    }

    /**
     * Check if the server is healthy by hitting GET /health.
     * Returns RUNNING (200 OK) or STOPPED (any failure).
     * Mutates [serverState] — use [probeServerHealth] when mutation is unwanted.
     */
    fun checkServerHealth(): ServerState {
        val result = probeServerHealth()
        synchronized(serverLock) {
            // Don't overwrite STARTING or ERROR — those transitions are managed explicitly.
            if (serverState != ServerState.STARTING && serverState != ServerState.ERROR) {
                serverState = result
            }
        }
        return result
    }

    /**
     * Start llama-server as a managed process. Returns true if server becomes healthy within 15s.
     * Must be called from a background thread.
     */
    fun startServer(): Boolean {
        val process: Process
        synchronized(serverLock) {
            // Guard against concurrent startServer() calls
            if (serverState == ServerState.STARTING) {
                logger.info("Server is already starting, ignoring concurrent startServer() call")
                return false
            }

            // Clear previous error state so we can retry
            if (serverState == ServerState.ERROR) {
                serverState = ServerState.STOPPED
                serverLogFile = null
            }

            val settings = SettingsState.getInstance()
            val rawCommand = settings.serverCommand

            if (rawCommand.isBlank()) {
                logger.warn("Cannot start server: server command is blank")
                return false
            }

            val (host, port) = extractHostPort(settings.serverUrl)
            val expandedCommand = rawCommand
                .replace("{{host}}", host)
                .replace("{{port}}", port.toString())

            serverState = ServerState.STARTING

            val command = tokenizeCommand(expandedCommand)

            logger.info("Starting managed server: ${command.joinToString(" ")}")

            val logDir = File(PathManager.getLogPath())
            val logFile = File(logDir, "completamente-server.log")
            serverLogFile = logFile

            try {
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
                managedProcess = process
                ownershipState = OwnershipState.MANAGED
                recentOutput.clear()
                startOutputReader(process, logFile)
            } catch (e: Exception) {
                logger.warn("Failed to start managed server: ${e.message}")
                serverState = ServerState.ERROR
                return false
            }
        }

        // Poll /health every 500ms for up to 15s — OUTSIDE the lock
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    logger.warn("Managed server process exited with code ${process.exitValue()}")
                    synchronized(serverLock) {
                        serverState = ServerState.ERROR
                        ownershipState = OwnershipState.UNMANAGED
                        managedProcess = null
                    }
                    return false
                }
                Thread.sleep(500)
                if (probeServerHealth() == ServerState.RUNNING) {
                    synchronized(serverLock) { serverState = ServerState.RUNNING }
                    logger.info("Managed server is healthy")
                    return true
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            // Fall through to timeout cleanup
        }

        logger.warn("Managed server did not become healthy within 15s")
        synchronized(serverLock) {
            managedProcess?.destroyForcibly()
            outputReaderThread?.interrupt()
            outputReaderThread = null
            managedProcess = null
            ownershipState = OwnershipState.UNMANAGED
            serverState = ServerState.ERROR
        }
        return false
    }

    private fun startOutputReader(process: Process, logFile: File?) {
        val thread = Thread({
            try {
                val writer = logFile?.bufferedWriter()
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            writer?.appendLine(line)
                            writer?.flush()
                            while (!recentOutput.offerLast(line)) {
                                recentOutput.pollFirst()
                            }
                        }
                    }
                } finally {
                    writer?.close()
                }
            } catch (_: Exception) {
                // Process destroyed or stream closed
            }
        }, "completamente-server-output")
        thread.isDaemon = true
        thread.start()
        outputReaderThread = thread
    }

    /**
     * Adopt an externally-started llama.cpp server already listening on the configured host:port.
     * Uses lsof/ps to discover the PID and log file. Must be called from a background thread.
     */
    fun adoptExternalServer() {
        synchronized(serverLock) {
            if (ownershipState == OwnershipState.MANAGED || serverState == ServerState.STARTING) return
        }

        if (probeServerHealth() != ServerState.RUNNING) return

        val settings = SettingsState.getInstance()
        val serverUrl = settings.serverUrl

        if (!isLocalUrl(serverUrl)) return

        val (_, port) = extractHostPort(serverUrl)

        val pid: Long
        try {
            val lsofProcess = ProcessBuilder("lsof", "-ti", "tcp:$port")
                .redirectErrorStream(true)
                .start()
            if (!lsofProcess.waitFor(5, TimeUnit.SECONDS)) {
                lsofProcess.destroyForcibly()
                logger.warn("lsof timed out for port $port")
                return
            }
            val lsofOutput = lsofProcess.inputStream.bufferedReader().readText().trim()
            if (lsofProcess.exitValue() != 0 || lsofOutput.isBlank()) {
                logger.warn("lsof did not find a process on port $port")
                return
            }
            // lsof may return multiple PIDs (one per line); take the first
            pid = lsofOutput.lines().first().trim().toLong()
        } catch (e: Exception) {
            logger.warn("Failed to run lsof for port $port: ${e.message}")
            return
        }

        var logFile: File? = null
        try {
            val psProcess = ProcessBuilder("ps", "-p", pid.toString(), "-o", "args=")
                .redirectErrorStream(true)
                .start()
            if (!psProcess.waitFor(5, TimeUnit.SECONDS)) {
                psProcess.destroyForcibly()
                logger.warn("ps timed out for PID $pid")
            } else {
                val cmdLine = psProcess.inputStream.bufferedReader().readText().trim()
                if (psProcess.exitValue() == 0 && cmdLine.isNotBlank()) {
                    logFile = parseLogFileFromArgs(cmdLine)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to run ps for PID $pid: ${e.message}")
            // Continue — log file is best-effort
        }

        synchronized(serverLock) {
            // Re-check guard: another thread may have started a managed server while we were running lsof/ps
            if (ownershipState == OwnershipState.MANAGED || serverState == ServerState.STARTING) return
            adoptedPid = pid
            ownershipState = OwnershipState.MANAGED
            serverState = ServerState.RUNNING
            if (logFile != null) serverLogFile = logFile
        }

        logger.info("Adopted external server (PID $pid, port $port, logFile=${logFile?.absolutePath ?: "none"})")
    }

    /**
     * Stop the managed server process if we own it.
     * Uses destroyForcibly() (SIGKILL) which is immediate, so no waiting is needed.
     */
    private fun stopServerLocked() {
        if (ownershipState != OwnershipState.MANAGED) return

        val process = managedProcess
        if (process != null) {
            logger.info("Stopping managed server")
            process.destroyForcibly()
            outputReaderThread?.interrupt()
            outputReaderThread = null
            managedProcess = null
            ownershipState = OwnershipState.UNMANAGED
            serverState = ServerState.STOPPED
            serverLogFile = null
            return
        }

        val pid = adoptedPid
        if (pid != null) {
            logger.info("Stopping adopted server (PID $pid)")
            try {
                val killProcess = ProcessBuilder("kill", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                if (!killProcess.waitFor(5, TimeUnit.SECONDS)) {
                    killProcess.destroyForcibly()
                }
                // Give the process a moment to terminate before checking
                Thread.sleep(1_000)
                // Escalate to SIGKILL if the process is still alive
                val checkProcess = ProcessBuilder("kill", "-0", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                if (checkProcess.waitFor(2, TimeUnit.SECONDS) && checkProcess.exitValue() == 0) {
                    logger.info("Adopted server PID $pid did not respond to SIGTERM, sending SIGKILL")
                    ProcessBuilder("kill", "-9", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(2, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                logger.warn("Failed to kill adopted server PID $pid: ${e.message}")
            }
            adoptedPid = null
            ownershipState = OwnershipState.UNMANAGED
            serverState = ServerState.STOPPED
            serverLogFile = null
        }
    }

    fun stopServer() {
        synchronized(serverLock) { stopServerLocked() }
    }

    override fun dispose() {
        stopServer()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // JVM already shutting down
        }
    }

    companion object {
        fun getInstance(): ServerManager =
            ApplicationManager.getApplication().getService(ServerManager::class.java)

        /** Returns true if the URL points to a local address (localhost, 127.x, ::1). */
        fun isLocalUrl(serverUrl: String): Boolean {
            return try {
                val uri = URI(serverUrl)
                val host = uri.host?.lowercase() ?: return false
                host == "localhost" || host.startsWith("127.") || host == "[::1]"
            } catch (_: Exception) {
                false
            }
        }

        /** Tokenize a command string, splitting on whitespace while respecting double-quoted segments. */
        fun tokenizeCommand(command: String): List<String> {
            val tokens = mutableListOf<String>()
            val regex = Regex(""""([^"]*)"|\S+""")
            regex.findAll(command).forEach { match ->
                tokens.add(match.groupValues[1].ifEmpty { match.value })
            }
            return tokens
        }

        /** Parse --log-file value from a command line string. Returns null if not found. */
        fun parseLogFileFromArgs(cmdLine: String): File? {
            // Match --log-file=<path> or --log-file <path>, with optional quoting
            val regex = Regex("""--log-file=(?:"([^"]+)"|'([^']+)'|(\S+))|--log-file\s+(?:"([^"]+)"|'([^']+)'|(\S+))""")
            val match = regex.find(cmdLine) ?: return null
            val path = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            return File(path)
        }

        /** Extract host and port from a server URL. Defaults to 127.0.0.1:8017 to match the plugin's default server URL. */
        fun extractHostPort(serverUrl: String): Pair<String, Int> {
            return try {
                val uri = URI(serverUrl)
                val host = if (uri.host == "localhost") "127.0.0.1" else (uri.host ?: "127.0.0.1")
                val port = if (uri.port > 0) uri.port else 8017
                host to port
            } catch (_: Exception) {
                "127.0.0.1" to 8017
            }
        }
    }
}
