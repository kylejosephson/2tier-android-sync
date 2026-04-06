package com.example.kylesmusicplayerandroid.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel

@Composable
fun AuthGateScreen(vm: AppViewModel) {

    // 🔑 Get Activity safely from Compose
    val activity = LocalContext.current as Activity

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Sign in to continue",
                style = MaterialTheme.typography.headlineSmall
            )

            Button(
                onClick = { vm.startLogin(activity) }
            ) {
                Text("Sign in with Microsoft")
            }
        }
    }
}
