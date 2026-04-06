package com.example.kylesmusicplayerandroid.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * AuthManager
 * Handles Microsoft OAuth2 PKCE login + silent token refresh.
 */
object AuthManager {

    private const val TAG = "AuthManager"

    // ============================================================
    // CONSTANTS
    // ============================================================
    internal const val CLIENT_ID = "66c23531-a11d-4108-b037-f7547b9ea2ee"

    internal const val REDIRECT_URI =
        "msauth://com.example.kylesmusicplayerandroid/zjXQbhsVb1j3bsV7Nf2oQdD5ZQ8%3D"

    private const val AUTH_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"

    internal const val TOKEN_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

    internal const val SCOPES =
        "offline_access Files.ReadWrite User.Read"

    private const val PREFS = "auth_prefs"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_EMAIL = "user_email"

    internal var appContext: Context? = null
    private val pendingVerifier = AtomicReference<String?>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============================================================
    // STATE
    // ============================================================
    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> get() = _accessToken

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> get() = _userEmail

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> get() = _isSignedIn

    // ============================================================
    // INITIALIZE
    // ============================================================
    fun initialize(context: Context) {
        appContext = context.applicationContext

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _accessToken.value = prefs.getString(KEY_ACCESS, null)
        _userEmail.value = prefs.getString(KEY_EMAIL, null)

        _isSignedIn.value = _accessToken.value != null
        Log.d(TAG, "Initialized — token=${_accessToken.value != null}")
    }

    // ============================================================
    // ENSURE VALID TOKEN (🔥 CORE FIX)
    // ============================================================
    suspend fun ensureValidAccessToken(): Boolean {
        val ctx = appContext ?: return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES, 0L)
        val now = System.currentTimeMillis() / 1000L

        // Token still valid
        if (!access.isNullOrBlank() && now < expiresAt - 60) {
            _accessToken.value = access
            _isSignedIn.value = true
            return true
        }

        // Cannot refresh
        if (refresh.isNullOrBlank()) {
            Log.e(TAG, "No refresh token available")
            clearTokens(ctx)
            return false
        }

        Log.d(TAG, "Refreshing access token…")

        val body = mapOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refresh
        )

        val result = try {
            Http.postForm(TOKEN_URL, body)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            null
        } ?: return false

        val json = JSONObject(result)

        if (json.has("error")) {
            Log.e(TAG, "Refresh error: ${json.optString("error_description")}")
            clearTokens(ctx)
            return false
        }

        val newAccess = json.optString("access_token", null)
        val newRefresh = json.optString("refresh_token", refresh)
        val expiresIn = json.optLong("expires_in", 3600)

        if (newAccess == null) {
            clearTokens(ctx)
            return false
        }

        saveTokens(ctx, newAccess, newRefresh, expiresIn)

        _accessToken.value = newAccess
        _isSignedIn.value = true

        Log.d(TAG, "Token refresh successful")
        return true
    }

    // ============================================================
    // SIGN-IN (only needed once)
    // ============================================================
    fun signIn(context: Context) {
        val (verifier, challenge) = generatePkce()
        pendingVerifier.set(verifier)

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val intent = Intent(Intent.ACTION_VIEW, authUri)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun handleRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code") ?: return
        val verifier = pendingVerifier.getAndSet(null) ?: return

        OAuthWorker.exchangeCodeForToken(code, verifier)
    }

    internal fun completeLogin(json: JSONObject) {
        val ctx = appContext ?: return

        val access = json.optString("access_token", null)
        val refresh = json.optString("refresh_token", null)
        val expires = json.optLong("expires_in", 3600)

        if (access == null || refresh == null) return

        saveTokens(ctx, access, refresh, expires)
        _accessToken.value = access
        _isSignedIn.value = true
    }

    // ============================================================
    // TOKEN STORAGE
    // ============================================================
    private fun saveTokens(context: Context, access: String, refresh: String, expires: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000L

        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRES, now + expires)
            .apply()
    }

    fun clearTokens(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        _accessToken.value = null
        _userEmail.value = null
        _isSignedIn.value = false
    }

    // ============================================================
    // PKCE
    // ============================================================
    private fun generatePkce(): Pair<String, String> {
        val random = SecureRandom()
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)

        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val sha256 = MessageDigest.getInstance("SHA-256")
        val challengeBytes = sha256.digest(verifier.toByteArray(Charsets.US_ASCII))

        val challenge = Base64.encodeToString(
            challengeBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        return verifier to challenge
    }
}
