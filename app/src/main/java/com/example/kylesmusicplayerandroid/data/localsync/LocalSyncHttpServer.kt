package com.example.kylesmusicplayerandroid.data.localsync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
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
    private val onReceiveFile: (query: Map<String, String>, body: ByteArray) -> Response,
    private val onSendFile: (query: Map<String, String>) -> Response,
    private val onDeleteFile: (query: Map<String, String>) -> Response,
    private val onRequestObserved: () -> Unit
) {

    data class Response(
        val statusCode: Int,
        val bodyBytes: ByteArray,
        val contentType: String = "application/json; charset=utf-8",
        val extraHeaders: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun json(statusCode: Int, body: JSONObject): Response {
                return Response(
                    statusCode = statusCode,
                    bodyBytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                )
            }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: ByteArray
    )

    companion object {
        private const val TAG = "LocalSyncHttpServer"
        private const val MAX_HEADER_BYTES = 64 * 1024
    }

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

            val request = try {
                parseRequest(socket.getInputStream())
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to parse request", t)
                writeResponse(
                    socket.getOutputStream(),
                    Response.json(
                        400,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "Malformed request")
                    )
                )
                return
            }

            val response = when (request.path) {
                "/ping" -> {
                    if (request.method != "GET") {
                        Response.json(405, JSONObject().put("ok", false).put("message", "Method not allowed"))
                    } else {
                        onRequestObserved()
                        onPing(request.query["protocolVersion"]?.toIntOrNull())
                    }
                }

                "/manifest" -> {
                    if (request.method != "GET") {
                        Response.json(405, JSONObject().put("ok", false).put("message", "Method not allowed"))
                    } else {
                        onRequestObserved()
                        onManifest()
                    }
                }

                "/receive-file" -> {
                    if (request.method != "POST") {
                        Response.json(405, JSONObject().put("ok", false).put("message", "Method not allowed"))
                    } else {
                        onRequestObserved()
                        onReceiveFile(request.query, request.body)
                    }
                }

                "/send-file" -> {
                    if (request.method != "GET") {
                        Response.json(405, JSONObject().put("ok", false).put("message", "Method not allowed"))
                    } else {
                        onRequestObserved()
                        onSendFile(request.query)
                    }
                }

                "/delete-file" -> {
                    if (request.method != "POST") {
                        Response.json(405, JSONObject().put("ok", false).put("message", "Method not allowed"))
                    } else {
                        onRequestObserved()
                        onDeleteFile(request.query)
                    }
                }

                else -> Response.json(
                    404,
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Not found")
                )
            }

            writeResponse(socket.getOutputStream(), response)
        }
    }

    private fun parseRequest(inputStream: InputStream): Request {
        val headerBytes = readHeaders(inputStream)
        val headerText = String(headerBytes, StandardCharsets.UTF_8)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        if (requestLine.isBlank()) {
            throw IllegalArgumentException("Request line missing")
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            throw IllegalArgumentException("Malformed request line")
        }

        val method = parts[0].uppercase(Locale.US)
        val rawTarget = parts[1]
        val contentLength = lines
            .drop(1)
            .mapNotNull { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex <= 0) return@mapNotNull null
                val name = line.substring(0, colonIndex).trim().lowercase(Locale.US)
                if (name != "content-length") return@mapNotNull null
                line.substring(colonIndex + 1).trim().toIntOrNull()
            }
            .firstOrNull()
            ?.coerceAtLeast(0)
            ?: 0

        val body = if (contentLength > 0) readExactly(inputStream, contentLength) else ByteArray(0)

        return Request(
            method = method,
            path = rawTarget.substringBefore('?'),
            query = parseQuery(rawTarget.substringAfter('?', "")),
            body = body
        )
    }

    private fun readHeaders(inputStream: InputStream): ByteArray {
        val headerBuffer = ArrayList<Byte>(512)
        var matched = 0

        while (true) {
            val next = inputStream.read()
            if (next < 0) {
                throw IllegalArgumentException("Unexpected EOF")
            }

            headerBuffer.add(next.toByte())
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }

            if (matched == 4) break
            if (headerBuffer.size > MAX_HEADER_BYTES) {
                throw IllegalArgumentException("Headers too large")
            }
        }

        return headerBuffer.toByteArrayCompat()
    }

    private fun readExactly(inputStream: InputStream, contentLength: Int): ByteArray {
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = inputStream.read(body, offset, contentLength - offset)
            if (read <= 0) {
                throw IllegalArgumentException("Unexpected EOF in body")
            }
            offset += read
        }
        return body
    }

    private fun writeResponse(outputStream: OutputStream, response: Response) {
        val bodyBytes = response.bodyBytes
        val statusText = when (response.statusCode) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "OK"
        }

        val headers = buildString {
            append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            response.extraHeaders.forEach { (key, value) ->
                append("$key: $value\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        outputStream.write(headers)
        outputStream.write(bodyBytes)
        outputStream.flush()
    }

    private fun ArrayList<Byte>.toByteArrayCompat(): ByteArray {
        val bytes = ByteArray(size)
        for (index in indices) {
            bytes[index] = this[index]
        }
        return bytes
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