package com.example.kylesmusicplayerandroid.auth

import android.app.Activity
import android.net.Uri
import android.os.Bundle

/**
 * OAuthRedirectActivity
 *
 * This Activity receives the redirect from Microsoft login:
 *
 *   msauth://com.example.kylesmusicplayerandroid/zjXQbhsVb1j3bsV7Nf2oQdD5ZQ8=
 *
 * Its only job is to pass the redirect URI to AuthManager,
 * which hands off to OAuthWorker for token exchange,
 * then close immediately.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri? = intent?.data

        if (uri != null) {
            AuthManager.handleRedirect(uri)
        }

        // Finish immediately — this Activity is invisible
        finish()
    }
}
