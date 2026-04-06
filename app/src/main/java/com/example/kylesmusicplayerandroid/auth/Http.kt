package com.example.kylesmusicplayerandroid.auth

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Simple HTTP helper for:
 *  - POST x-www-form-urlencoded
 *  - GET JSON with Authorization header
 */

object Http {

    // ------------------------------------------------------------
    // POST (already existed)
    // ------------------------------------------------------------
    fun postForm(url: String, form: Map<String, String>): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )

            // Encode form body
            val body = form.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            // Send body
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { out ->
                out.write(body)
            }

            // Response
            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            response

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ------------------------------------------------------------
    // GET JSON (needed by AuthManager)
    // ------------------------------------------------------------
    fun getJson(url: String, accessToken: String?): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"

            if (accessToken != null) {
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
            }

            val response = conn.inputStream.bufferedReader()
                .use(BufferedReader::readText)

            conn.disconnect()
            response

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
