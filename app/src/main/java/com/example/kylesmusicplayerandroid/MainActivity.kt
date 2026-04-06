package com.example.kylesmusicplayerandroid

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.auth.AudioPermission
import com.example.kylesmusicplayerandroid.auth.AuthManager
import com.example.kylesmusicplayerandroid.data.service.MediaPlaybackService
import com.example.kylesmusicplayerandroid.ui.theme.AppRoot
import com.example.kylesmusicplayerandroid.ui.theme.KylesMusicPlayerAndroidTheme
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModelFactory

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels {
        AppViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Normal app startup
        AuthManager.initialize(applicationContext)

        setContent {
            KylesMusicPlayerAndroidTheme {

                var audioReady by remember { mutableStateOf(false) }

                // -------------------------------------------------------
                // Permission launcher (Android 13+ uses READ_MEDIA_AUDIO)
                // -------------------------------------------------------
                val requestAudioPermission = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        // ✅ Model B: start playback service AFTER we have audio permission.
                        // This keeps MediaSession authoritative and avoids early-start weirdness.
                        MediaPlaybackService.ensureServiceRunning(applicationContext)

                        vm.initialize(this@MainActivity)
                        vm.onAudioPermissionGranted()
                        audioReady = true
                    } else {
                        audioReady = false
                    }
                }

                // -------------------------------------------------------
                // Step 1: audio permission + VM init (ONLY)
                // -------------------------------------------------------
                LaunchedEffect(Unit) {
                    if (AudioPermission.hasPermission(this@MainActivity)) {
                        // ✅ Model B: start playback service AFTER we have audio permission.
                        MediaPlaybackService.ensureServiceRunning(applicationContext)

                        vm.initialize(this@MainActivity)
                        vm.onAudioPermissionGranted()
                        audioReady = true
                    } else {
                        audioReady = false
                        val permission =
                            if (Build.VERSION.SDK_INT >= 33)
                                Manifest.permission.READ_MEDIA_AUDIO
                            else
                                Manifest.permission.READ_EXTERNAL_STORAGE

                        requestAudioPermission.launch(permission)
                    }
                }

                // -------------------------------------------------------
                // Router UI
                // -------------------------------------------------------
                if (!audioReady) {
                    GateScaffold(
                        title = "Starting…",
                        message = "Waiting for audio permission…"
                    )
                } else {
                    // After permission is granted, AppRoot controls:
                    // - OneDrive auth gate
                    // - SD SAF gate
                    // - IndexBuild gate
                    // - Player
                    AppRoot(vm = vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.stopSyncMode()
    }
}

/* ---------------- Gate UI ---------------- */

@Composable
private fun GateScaffold(
    title: String,
    message: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(actionText) }
            }
        }
    }
}
