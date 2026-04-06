package com.example.kylesmusicplayerandroid.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.data.service.MediaPlaybackService
import com.example.kylesmusicplayerandroid.ui.screens.LibraryScreen
import com.example.kylesmusicplayerandroid.ui.screens.PlayerScreen
import com.example.kylesmusicplayerandroid.ui.screens.PlaylistScreen
import com.example.kylesmusicplayerandroid.ui.screens.SyncScreen
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        MediaPlaybackService.ensureServiceRunning(ctx.applicationContext)
        vm.onSyncTabOpened()
    }

    val matrixBg = Color(0xFF040805)
    val matrixPanel = Color(0xFF06130A)
    val matrixGreen = Color(0xFF00FF66)
    val matrixGreenSoft = matrixGreen.copy(alpha = 0.55f)
    val matrixGreenFaint = matrixGreen.copy(alpha = 0.25f)
    val textSelected = matrixGreen.copy(alpha = 0.92f)
    val textUnselected = matrixGreen.copy(alpha = 0.65f)

    var currentTab by remember { mutableStateOf(AppTab.PLAYER) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(matrixBg)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = matrixPanel,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            TabRow(
                selectedTabIndex = currentTab.ordinal,
                modifier = Modifier.fillMaxWidth(),
                containerColor = matrixPanel,
                contentColor = textSelected,
                indicator = { tabPositions ->
                    val pos = tabPositions[currentTab.ordinal]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentSize(Alignment.BottomStart)
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(x = pos.left)
                                .width(pos.width)
                                .height(3.dp)
                                .background(matrixGreenSoft)
                        )
                    }
                },
                divider = {
                    Divider(color = matrixGreenFaint, thickness = 1.dp)
                }
            ) {
                AppTab.values().forEachIndexed { index, tab ->
                    Tab(
                        selected = currentTab.ordinal == index,
                        onClick = {
                            if (tab == currentTab) return@Tab

                            if (currentTab == AppTab.SYNC && tab != AppTab.SYNC) {
                                vm.onSyncTabClosed()
                            }

                            currentTab = tab

                            if (tab == AppTab.SYNC) {
                                vm.onSyncTabOpened()
                            }
                        },
                        text = { Text(tab.label) },
                        selectedContentColor = textSelected,
                        unselectedContentColor = textUnselected
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when (currentTab) {
            AppTab.PLAYER -> PlayerScreen(vm)
            AppTab.PLAYLIST -> PlaylistScreen(vm)
            AppTab.LIBRARY -> LibraryScreen(vm)
            AppTab.SYNC -> SyncScreen(vm)
        }
    }
}

enum class AppTab(val label: String) {
    PLAYER("Player"),
    PLAYLIST("Playlist"),
    LIBRARY("Library"),
    SYNC("Sync")
}
