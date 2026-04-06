package com.example.kylesmusicplayerandroid.domain.usecase.auth

import android.content.Context
import com.example.kylesmusicplayerandroid.auth.AuthManager

class SignOutUseCase {

    fun execute(context: Context) {
        AuthManager.clearTokens(context)
    }
}
