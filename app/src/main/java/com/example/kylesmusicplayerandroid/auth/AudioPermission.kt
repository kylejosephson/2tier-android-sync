package com.example.kylesmusicplayerandroid.auth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object AudioPermission {

    fun hasPermission(context: Context): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= 33)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}
