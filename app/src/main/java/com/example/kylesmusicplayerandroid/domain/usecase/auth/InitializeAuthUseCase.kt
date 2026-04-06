package com.example.kylesmusicplayerandroid.domain.usecase.auth

import android.content.Context
import com.example.kylesmusicplayerandroid.auth.AuthManager

class InitializeAuthUseCase {

    fun execute(context: Context) {
        AuthManager.initialize(context)
    }
}


