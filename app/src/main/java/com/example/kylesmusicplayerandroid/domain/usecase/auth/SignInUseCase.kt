package com.example.kylesmusicplayerandroid.domain.usecase.auth

import android.app.Activity
import com.example.kylesmusicplayerandroid.auth.AuthManager

class SignInUseCase {

    fun execute(activity: Activity) {
        AuthManager.signIn(activity)
    }
}
