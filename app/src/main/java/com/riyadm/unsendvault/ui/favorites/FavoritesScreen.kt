package com.riyadm.unsendvault.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.EmptyState
import com.riyadm.unsendvault.ui.components.MessageRowConfig
import com.riyadm.unsendvault.ui.components.MessageRowContent

@Composable
fun FavoritesRoute(viewModel: FavoritesViewModel, onOpenMessage: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FavoritesScreen(state, onOpenMessage, viewModel::removeFavorite)
}

@Composable
fun FavoritesScreen(state: FavoritesUiState, onOpenMessage: (Long) -> Unit, onRemoveFavorite: (Long) -> Unit) {
    val formatter = rememberTimeFormatter()
    val removeLabel = stringResource(R.string.action_remove_favorite)
    AppScaffold(title = stringResource(R.string.favorites_title)) { padding ->
        if (!state.loading && state.messages.isEmpty()) {
            EmptyState(
                Icons.Outlined.StarBorder,
                stringResource(R.string.favorites_empty_title),
                stringResource(R.string.favorites_empty_body),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("favorites_list")) {
                items(state.messages, key = { it.id }, contentType = { "message" }) { message ->
                    MessageRowContent(
                        message = message,
                        timeLabel = formatter.short(message.messageTimestamp),
                        config = MessageRowConfig(),
                        modifier = Modifier
                            .clickable(role = Role.Button) { onOpenMessage(message.id) }
                            .semantics {
                                customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemoveFavorite(message.id); true })
                            },
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}
