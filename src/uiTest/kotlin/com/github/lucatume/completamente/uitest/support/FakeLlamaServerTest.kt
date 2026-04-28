package com.github.lucatume.completamente.uitest.support

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FakeLlamaServerTest {

    private lateinit var server: FakeLlamaServer
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Before fun setUp() { server = FakeLlamaServer().also { it.start() } }
    @After fun tearDown() { server.stop() }

    @Test fun testHealthEndpointAlwaysReturns200() {
        val response = get("/health")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\""))
    }

    @Test fun testInfillReturnsEnqueuedContent() {
        server.enqueueInfill(content = "println(\"hi\")")
        val response = post("/infill", "{\"prompt\":\"x\"}")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("println"))
    }

    @Test fun testInfillFallsBackToEmptyWhenNothingEnqueued() {
        val response = post("/infill", "{\"prompt\":\"x\"}")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"content\":\"\""))
    }

    @Test fun testRecordsAllRequests() {
        post("/infill", "{\"a\":1}")
        post("/infill", "{\"b\":2}")
        assertEquals(2, server.requests.size)
        assertEquals("/infill", server.requests[0].path)
        assertEquals("{\"a\":1}", server.requests[0].body)
    }

    @Test fun testEnqueueRawWithCustomStatus() {
        server.enqueueRaw("/infill", body = "{\"err\":\"boom\"}", status = 500)
        val response = post("/infill", "{}")
        assertEquals(500, response.statusCode())
    }

    private fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder().uri(URI.create("${server.baseUrl}$path")).GET().build()
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder().uri(URI.create("${server.baseUrl}$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
