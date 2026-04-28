package com.github.lucatume.completamente.uitest.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.Collections

/**
 * In-process stand-in for a llama.cpp server. Endpoints: /health, /infill.
 * Responses are FIFO-staged via [enqueueInfill] / [enqueueRaw]; unstaged /infill
 * requests fall back to an empty completion. All requests are recorded.
 */
class FakeLlamaServer {

    private var server: HttpServer? = null
    private val recorded: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val infillQueue: ArrayDeque<StagedResponse> = ArrayDeque()
    private val rawQueues: MutableMap<String, ArrayDeque<StagedResponse>> = mutableMapOf()

    val requests: List<RecordedRequest> get() = recorded.toList()
    val baseUrl: String get() = "http://127.0.0.1:${server!!.address.port}"

    fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/health") { ex -> handleHealth(ex) }
        s.createContext("/infill") { ex -> handleInfill(ex) }
        s.executor = null
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    @Synchronized
    fun enqueueInfill(content: String, stop: List<String> = emptyList()) {
        val stopJson = stop.joinToString(prefix = "[", postfix = "]") { "\"${escape(it)}\"" }
        val body = "{\"content\":\"${escape(content)}\",\"stop\":$stopJson}"
        infillQueue.add(StagedResponse(status = 200, body = body))
    }

    @Synchronized
    fun enqueueRaw(endpoint: String, body: String, status: Int = 200) {
        val q = rawQueues.getOrPut(endpoint) { ArrayDeque() }
        q.add(StagedResponse(status = status, body = body))
    }

    @Synchronized
    fun reset() {
        recorded.clear()
        infillQueue.clear()
        rawQueues.clear()
    }

    private fun handleHealth(ex: HttpExchange) {
        record(ex)
        respond(ex, 200, "{\"status\":\"ok\"}")
    }

    private fun handleInfill(ex: HttpExchange) {
        record(ex)
        val staged = synchronized(this) {
            rawQueues["/infill"]?.pollFirst() ?: infillQueue.pollFirst()
        }
        if (staged != null) {
            respond(ex, staged.status, staged.body)
        } else {
            respond(ex, 200, "{\"content\":\"\",\"stop\":[]}")
        }
    }

    private fun record(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = ex.requestHeaders.entries.associate { it.key to (it.value.firstOrNull() ?: "") }
        recorded.add(RecordedRequest(ex.requestMethod, ex.requestURI.path, headers, body))
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun escape(s: String) = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private data class StagedResponse(val status: Int, val body: String)
}
