package com.example.kylesmusicplayerandroid.auth

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * OAuthWorker
 *
 * Exchanges authorization CODE for ACCESS + REFRESH tokens.
 * Runs network operations on background dispatcher.
 */
object OAuthWorker {

    private const val TAG = "OAuthWorker"

    // Shared background scope for network operations
    private val scope = CoroutineScope(Dispatchers.IO)

    fun exchangeCodeForToken(code: String, verifier: String) {

        val ctx = AuthManager.appContext
        if (ctx == null) {
            Log.e(TAG, "❌ App context is null — aborting token exchange")
            return
        }

        Log.d(TAG, "➡️ Starting token exchange")
        Log.d(TAG, "Auth code length = ${code.length}")
        Log.d(TAG, "Using redirect URI = ${AuthManager.REDIRECT_URI}")

        scope.launch {

            // 🔑 CRITICAL FIX: scope MUST be included here
            val body = mapOf(
                "client_id" to AuthManager.CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to AuthManager.REDIRECT_URI,
                "code_verifier" to verifier,
                "scope" to AuthManager.SCOPES
            )

            val result = withContext(Dispatchers.IO) {
                try {
                    Http.postForm(
                        url = AuthManager.TOKEN_URL,
                        form = body
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Token request failed", e)
                    null
                }
            }

            if (result == null) {
                Log.e(TAG, "❌ Token endpoint returned null response")
                return@launch
            }

            Log.d(TAG, "⬅️ Token response received")
            Log.d(TAG, result)

            try {
                val json = JSONObject(result)

                if (json.has("error")) {
                    Log.e(TAG, "❌ OAuth error: ${json.optString("error")}")
                    Log.e(TAG, "Description: ${json.optString("error_description")}")
                    return@launch
                }

                AuthManager.completeLogin(json)
                Log.d(TAG, "✅ Token exchange successful")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse token JSON", e)
            }
        }
    }
}
