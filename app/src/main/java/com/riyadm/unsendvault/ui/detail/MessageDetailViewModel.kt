package com.riyadm.unsendvault.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class MessageDetailUiState(
    val loading: Boolean = true,
    val detail: MessageDetail? = null,
)

class MessageDetailViewModel(
    private val messageId: Long,
    private val vault: VaultRepository,
) : ViewModel() {
    private val _deleted = Channel<Unit>(Channel.CONFLATED)

    /** Emits once after the message was deleted, so the screen can navigate back. */
    val deleted: Flow<Unit> = _deleted.receiveAsFlow()

    val state: StateFlow<MessageDetailUiState> = vault.observeMessage(messageId)
        .map { MessageDetailUiState(loading = false, detail = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageDetailUiState())

    fun toggleFavorite() {
        val message = state.value.detail?.message ?: return
        viewModelScope.launch { vault.setFavorite(message.id, !message.isFavorite) }
    }

    fun delete() {
        viewModelScope.launch {
            vault.deleteMessages(listOf(messageId))
            _deleted.send(Unit)
        }
    }
}
