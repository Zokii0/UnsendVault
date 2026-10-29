package com.riyadm.unsendvault.ui.favorites

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class FavoritesUiState(val loading: Boolean = true, val messages: List<ArchivedMessage> = emptyList())

class FavoritesViewModel(private val vault: VaultRepository) : ViewModel() {
    val state: StateFlow<FavoritesUiState> = vault.observeFavorites(LIMIT)
        .map { FavoritesUiState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    fun removeFavorite(id: Long) {
        viewModelScope.launch { vault.setFavorite(id, false) }
    }

    companion object {
        const val LIMIT = 1_000
    }
}
