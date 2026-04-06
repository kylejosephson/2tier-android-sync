package com.example.kylesmusicplayerandroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.kylesmusicplayerandroid.domain.usecase.auth.SignInUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.auth.SignOutUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.playlist.ComparePlaylistsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.playlist.LoadPlaylistsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.playlist.SyncPlaylistsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.sync.CompareSongsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.sync.LoadMetadataUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.sync.SyncSongsUseCase

class AppViewModelFactory : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {

            val loadMetadataUseCase = LoadMetadataUseCase()
            val loadPlaylistsUseCase = LoadPlaylistsUseCase()
            val comparePlaylistsUseCase = ComparePlaylistsUseCase()
            val syncPlaylistsUseCase = SyncPlaylistsUseCase()
            val compareSongsUseCase = CompareSongsUseCase()
            val syncSongsUseCase = SyncSongsUseCase()

            val signInUseCase = SignInUseCase()
            val signOutUseCase = SignOutUseCase()

            @Suppress("UNCHECKED_CAST")
            return AppViewModel(
                loadMetadataUseCase,
                loadPlaylistsUseCase,
                comparePlaylistsUseCase,
                syncPlaylistsUseCase,
                compareSongsUseCase,
                syncSongsUseCase,
                signInUseCase,
                signOutUseCase
            ) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
