package com.example.kylesmusicplayerandroid.data.localsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class LocalSyncHttpServer(
    private val port: Int,
    private val onPing: (protocolVersion: Int?) -> Response,
    private val onManifest: () -> Response,
    private val onRequestObserved: () -> Unit
) {

    data class Response(
        val statusCode: Int,
        val body: JSONObject
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Synchronized
    fun start() {
        if (serverSocket != null) return

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket

        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }

                launch {
                    handleClient(client)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5000

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            if (requestLine.isBlank()) return

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeJson(socket.getOutputStream(), 400, JSONObject().put("ok", false).put("message", "Malformed request"))
                return
            }

            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]

            if (method != "GET") {
                writeJson(socket.getOutputStream(), 405, JSONObject().put("ok", false).put("message", "Method not allowed"))
                return
            }

            val path = rawTarget.substringBefore('?')
            val query = parseQuery(rawTarget.substringAfter('?', ""))

            val response = when (path) {
                "/ping" -> {
                    onRequestObserved()
                    onPing(query["protocolVersion"]?.toIntOrNull())
                }
                "/manifest" -> {
                    onRequestObserved()
                    onManifest()
                }
                else -> Response(
                    statusCode = 404,
                    body = JSONObject()
                        .put("ok", false)
                        .put("message", "Not found")
                )
            }

            writeJson(socket.getOutputStream(), response.statusCode, response.body)
        }
    }

    private fun writeJson(outputStream: OutputStream, statusCode: Int, body: JSONObject) {
        val bodyBytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "OK"
        }

        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        outputStream.write(headers)
        outputStream.write(bodyBytes)
        outputStream.flush()
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()

        return rawQuery.split("&")
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val key = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8.name())
                key to value
            }
            .toMap()
    }
}
