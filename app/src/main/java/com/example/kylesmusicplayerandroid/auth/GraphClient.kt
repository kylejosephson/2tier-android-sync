package com.example.kylesmusicplayerandroid.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

object GraphClient {

    private const val TAG = "GraphClient"
    private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------
    // TOKEN
    // ------------------------------------------------------------
    private fun tokenFromState(): String? {
        val raw = AuthManager.accessToken.value ?: return null
        val t = raw.trim()
        if (t.isBlank()) return null

        return if (t.startsWith("Bearer ", ignoreCase = true)) {
            t.substringAfter("Bearer ", "").trim().ifBlank { null }
        } else {
            t
        }
    }

    private fun toGraphUrl(pathOrUrl: String): String {
        val s = pathOrUrl.trim()
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            return s
        }
        return if (s.startsWith("/")) "$GRAPH_BASE$s" else "$GRAPH_BASE/$s"
    }

    // ------------------------------------------------------------
    // RAW GET (returns Response; caller MUST close)
    // suspend: hard-gated by ensureValidAccessToken()
    // ------------------------------------------------------------
    private suspend fun rawGet(pathOrUrl: String, allowRefreshRetry: Boolean): Response? {
        val url = toGraphUrl(pathOrUrl)

        val ok = AuthManager.ensureValidAccessToken()
        if (!ok) {
            Log.e(TAG, "🔥 No valid access token available (ensureValidAccessToken=false) for $url")
            return null
        }

        val t = tokenFromState()
        if (t.isNullOrBlank()) {
            Log.e(TAG, "🔥 Token state is blank/null even after ensureValidAccessToken() for $url")
            return null
        }

        // Diagnostic (safe): head + length only. Do NOT validate dot-count.
        val head = if (t.length >= 12) t.substring(0, 12) else t
        Log.w(TAG, "Token diag: len=${t.length} head='${head}…'")

        Log.d(TAG, "GET $url")

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $t")
            .build()

        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            Log.e(TAG, "🔥 HTTP exception for $url", e)
            return null
        }

        // If 401, attempt ONE refresh pass and retry ONCE.
        if (resp.code == 401 && allowRefreshRetry) {
            resp.close()
            Log.w(TAG, "401 for $url. Refreshing token and retrying once...")
            val ok2 = AuthManager.ensureValidAccessToken()
            if (!ok2) {
                Log.e(TAG, "🔥 Token refresh failed after 401 for $url")
                return null
            }
            return rawGet(pathOrUrl, allowRefreshRetry = false)
        }

        Log.d(TAG, "Response ${resp.code} for $url")
        return resp
    }

    private suspend fun rawGet(pathOrUrl: String): Response? {
        return rawGet(pathOrUrl, allowRefreshRetry = true)
    }

    // ------------------------------------------------------------
    // JSON GET
    // ------------------------------------------------------------
    suspend fun getJson(pathOrUrl: String): JSONObject? {
        val resp = rawGet(pathOrUrl) ?: return null

        resp.use { r ->
            val bodyStr = try {
                r.body?.string()
            } catch (t: Throwable) {
                Log.e(TAG, "🔥 Failed reading response body for $pathOrUrl", t)
                null
            }

            if (!r.isSuccessful) {
                val snippet = bodyStr?.take(800)
                Log.e(TAG, "🔥 Graph error ${r.code} for $pathOrUrl\n$snippet")
                return null
            }

            if (bodyStr.isNullOrBlank()) {
                Log.e(TAG, "🔥 Empty response body for $pathOrUrl")
                return null
            }

            return try {
                JSONObject(bodyStr)
            } catch (e: Exception) {
                Log.e(TAG, "🔥 JSON parse error for $pathOrUrl\n${bodyStr.take(800)}", e)
                null
            }
        }
    }

    // ------------------------------------------------------------
    // DOWNLOAD STREAM
    // ------------------------------------------------------------
    suspend fun downloadFileStream(pathOrUrl: String): InputStream {
        val resp = rawGet(pathOrUrl)
            ?: throw IllegalStateException("Graph download failed: rawGet() returned null for $pathOrUrl")

        if (!resp.isSuccessful) {
            val code = resp.code
            val errBody = try { resp.body?.string()?.take(800) } catch (_: Throwable) { null }
            resp.close()
            throw IllegalStateException("Graph download failed HTTP $code for $pathOrUrl\n${errBody ?: ""}")
        }

        val bodyStream = resp.body?.byteStream()
            ?: run {
                resp.close()
                throw IllegalStateException("Graph download failed: empty body for $pathOrUrl")
            }

        return object : FilterInputStream(bodyStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    resp.close()
                }
            }
        }
    }

    suspend fun download(pathOrUrl: String): InputStream? {
        return try {
            downloadFileStream(pathOrUrl)
        } catch (t: Throwable) {
            Log.e(TAG, "🔥 Download failed for $pathOrUrl", t)
            null
        }
    }

    // ------------------------------------------------------------
    // DELTA
    // ------------------------------------------------------------
    suspend fun runDelta(startPathOrUrl: String): DeltaResult {
        val items = mutableListOf<JSONObject>()
        var nextUrl: String? = startPathOrUrl.trim().ifBlank { null }
        var deltaLink: String? = null

        var pages = 0
        while (nextUrl != null) {
            pages++

            val json = getJson(nextUrl)
            if (json == null) {
                Log.e(TAG, "🔥 Delta page fetch failed (pagesFetched=$pages). Stopping delta early. url=$nextUrl")
                break
            }

            val values = json.optJSONArray("value")
            if (values != null) {
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { items.add(it) }
                }
            }

            val nl = json.optString("@odata.nextLink", "")
                .trim()
                .ifBlank { null }

            val dl = json.optString("@odata.deltaLink", "")
                .trim()
                .ifBlank { null }

            nextUrl = nl
            if (dl != null) deltaLink = dl
        }

        return DeltaResult(items = items, deltaLink = deltaLink)
    }

    data class DeltaResult(
        val items: List<JSONObject>,
        val deltaLink: String?
    )
}
