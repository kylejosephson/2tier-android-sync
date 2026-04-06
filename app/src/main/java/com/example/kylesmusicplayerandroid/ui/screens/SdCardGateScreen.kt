package com.example.kylesmusicplayerandroid.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.R
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel
import kotlinx.coroutines.delay

@Composable
fun SdCardGateScreen(
    vm: AppViewModel,
    onGranted: () -> Unit
) {
    // Keep the device awake while we're on this gate (prevents sleep killing ADB/logcat)
    val view = LocalView.current
    DisposableEffect(Unit) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }

    // Matrix-ish look (simple + safe)
    val MatrixBg = Color(0xFF040805)
    val MatrixGreen = Color(0xFF00FF66)
    val MatrixGreenSoft = MatrixGreen.copy(alpha = 0.55f)
    val MatrixGreenFaint = MatrixGreen.copy(alpha = 0.25f)

    // This is the single source of truth now:
    // TRUE means: stored tree URI exists AND persisted SAF permission exists.
    val hasMusicUri by vm.hasMusicUri.collectAsState(initial = false)

    var status by remember { mutableStateOf("SD Card access is required for Song Sync.") }
    var grantedThisSession by remember { mutableStateOf(false) }
    var launchedOnce by remember { mutableStateOf(false) }

    val pickTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            status = "No folder selected. Please pick your SD card Music folder."
            return@rememberLauncherForActivityResult
        }

        val ok = vm.persistMusicTreeUri(uri)
        if (ok) {
            grantedThisSession = true
            status = "✅ SD Card folder granted. Returning to app…"
        } else {
            status = "❌ Failed to persist SD Card permission. Try again."
        }
    }

    // If permission is ALREADY valid, skip picker entirely and continue.
    LaunchedEffect(hasMusicUri) {
        if (hasMusicUri && !grantedThisSession) {
            grantedThisSession = true
            status = "✅ SD Card permission already granted. Returning to app…"
            delay(300)
            onGranted()
        }
    }

    // Auto-launch the picker once only if we do NOT have valid permission.
    LaunchedEffect(hasMusicUri) {
        if (!hasMusicUri && !launchedOnce) {
            launchedOnce = true
            status = "Select your SD card Music folder…"
            pickTree.launch(null)
        }
    }

    // Optional: auto-continue shortly after success (from picker flow)
    LaunchedEffect(grantedThisSession) {
        if (grantedThisSession && !hasMusicUri) {
            // When we got here via picker, we delay slightly then continue.
            // (If hasMusicUri was already true, the other LaunchedEffect handles it.)
            delay(600)
            onGranted()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MatrixBg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SD Card Permission",
                style = MaterialTheme.typography.headlineSmall,
                color = MatrixGreen
            )

            Spacer(Modifier.height(12.dp))

            // Spinner + dots heartbeat (logo)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                SpinningLogo()
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (grantedThisSession) {
                        "Granted" + AnimatedDots()
                    } else {
                        "Working" + AnimatedDots()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MatrixGreen.copy(alpha = 0.80f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, MatrixGreenSoft, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "Pick your SD card Music folder once. This enables Song Sync (hash/index).",
                        color = MatrixGreen.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = status,
                        color = MatrixGreen.copy(alpha = 0.75f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    status = "Select your SD card Music folder…"
                    pickTree.launch(null)
                },
                enabled = !hasMusicUri
            ) {
                Text("Choose SD Card Music Folder")
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = { onGranted() },
                enabled = grantedThisSession
            ) {
                Text("Continue")
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Tip: choose the folder that contains your Artist folders.",
                color = MatrixGreenFaint
            )
        }
    }
}

@Composable
private fun SpinningLogo() {
    val rotation by rememberInfiniteTransition(label = "sdGateLogoSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sdGateLogoRotation"
    )

    Image(
        painter = painterResource(id = R.drawable.app_logo),
        contentDescription = "Loading",
        modifier = Modifier
            .size(44.dp)
            .rotate(rotation)
    )
}

@Composable
private fun AnimatedDots(): String {
    var dots by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            dots = (dots + 1) % 4
            delay(450)
        }
    }
    return when (dots) {
        0 -> ""
        1 -> "."
        2 -> ".."
        else -> "..."
    }
}
