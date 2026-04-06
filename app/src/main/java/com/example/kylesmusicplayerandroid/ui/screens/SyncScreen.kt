package com.example.kylesmusicplayerandroid.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.data.localsync.SyncServerStatus
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel

@Composable
fun SyncScreen(vm: AppViewModel) {
    val state by vm.localSyncUiState.collectAsState()
    val scrollState = rememberScrollState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        vm.onManagedRootSelected(uri)
    }

    val matrixBg = Color(0xFF040805)
    val matrixPanel = Color(0xFF06130A)
    val matrixPanelAlt = Color(0xFF040E07)
    val matrixGreen = Color(0xFF00FF66)
    val matrixGreenSoft = matrixGreen.copy(alpha = 0.55f)
    val matrixGreenFaint = matrixGreen.copy(alpha = 0.22f)
    val textPrimary = matrixGreen.copy(alpha = 0.92f)
    val textMuted = matrixGreen.copy(alpha = 0.68f)
    val warning = Color(0xFFFFC857)

    val panelShape = RoundedCornerShape(12.dp)

    fun Modifier.syncPanel(background: Color = matrixPanel): Modifier =
        this.background(background, panelShape)
            .border(1.dp, matrixGreenSoft, panelShape)

    fun serverStateLabel(status: SyncServerStatus): String {
        return when (status) {
            SyncServerStatus.STOPPED -> "Stopped"
            SyncServerStatus.STARTING -> "Starting"
            SyncServerStatus.WAITING_FOR_DESKTOP -> "Waiting for Desktop"
            SyncServerStatus.CONNECTED -> "Connected"
        }
    }

    fun syncReadinessLabel(): String {
        return when {
            !state.managedRootSelected -> "Setup incomplete"
            state.initialSyncComplete -> "Ready for normal sync"
            else -> "Ready for initial sync"
        }
    }

    @Composable
    fun statusValue(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = textMuted, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                color = textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    @Composable
    fun actionButton(
        text: String,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, matrixGreenSoft),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = matrixPanel,
                contentColor = textPrimary,
                disabledContentColor = textMuted,
                disabledContainerColor = matrixPanel
            )
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(matrixBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .syncPanel(),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Sync",
                        style = MaterialTheme.typography.headlineSmall,
                        color = textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.statusMessage,
                        color = textMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .syncPanel(matrixPanelAlt),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Sync Status",
                        style = MaterialTheme.typography.titleLarge,
                        color = textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(12.dp))
                    statusValue("Managed Folder", if (state.managedRootSelected) "Selected" else "Not selected")
                    Spacer(Modifier.height(8.dp))
                    statusValue("Folder Name", state.managedRootLabel)
                    Spacer(Modifier.height(8.dp))
                    statusValue("Initialization", if (state.initialSyncComplete) "Initialized" else "Not initialized")
                    Spacer(Modifier.height(8.dp))
                    statusValue("Sync Readiness", syncReadinessLabel())
                    Spacer(Modifier.height(8.dp))
                    statusValue("Wi-Fi Server", serverStateLabel(state.serverStatus))

                    Spacer(Modifier.height(12.dp))
                    Divider(color = matrixGreenFaint)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Connection",
                        style = MaterialTheme.typography.titleMedium,
                        color = textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    statusValue("Device Name", state.deviceName)
                    Spacer(Modifier.height(8.dp))
                    statusValue("IP Address", state.ipAddress ?: "Unavailable")
                    Spacer(Modifier.height(8.dp))
                    statusValue("Port", state.port.toString())
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .syncPanel(matrixPanelAlt),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Managed Folder",
                        style = MaterialTheme.typography.titleLarge,
                        color = textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.managedRootSelected) {
                            "Android will preserve the exact folder structure inside the selected root."
                        } else {
                            "Choose the folder that will become the managed Android music library."
                        },
                        color = textMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Initial sync warning is staged for the next phase. Selecting a folder now does not clear it yet.",
                        color = warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .syncPanel(),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.titleLarge,
                        color = textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))

                    actionButton(
                        text = "Select Music Folder",
                        enabled = true
                    ) {
                        folderPicker.launch(null)
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            actionButton(
                                text = "Start Sync Mode",
                                enabled = state.managedRootSelected && !state.syncModeActive
                            ) {
                                vm.startSyncMode()
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            actionButton(
                                text = "Stop Sync Mode",
                                enabled = state.syncModeActive
                            ) {
                                vm.stopSyncMode()
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .syncPanel(matrixPanelAlt),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Phase A Notes",
                        style = MaterialTheme.typography.titleMedium,
                        color = textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Normal sync requires initialSyncComplete = true, so /manifest will reject requests until the first full desktop sync is done.",
                        color = textMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
