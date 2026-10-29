package com.riyadm.unsendvault.ui.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar-day range as returned by the Material date picker (UTC midnight millis, inclusive). */
@Immutable
data class DayRange(val startUtcMillis: Long, val endUtcMillis: Long)

@Immutable
data class SearchFilters(
    val packages: Set<String> = emptySet(),
    val conversationId: Long? = null,
    val dayRange: DayRange? = null,
    val favoritesOnly: Boolean = false,
    val possiblyDeletedOnly: Boolean = false,
) {
    val active: Boolean
        get() = packages.isNotEmpty() || conversationId != null || dayRange != null || favoritesOnly || possiblyDeletedOnly
    val activeCount: Int
        get() = listOf(packages.isNotEmpty(), conversationId != null, dayRange != null, favoritesOnly, possiblyDeletedOnly).count { it }
}

@Immutable
data class SearchUiState(
    /** The query the results belong to (debounced), used for match highlighting. */
    val appliedQuery: String = "",
    val filters: SearchFilters = SearchFilters(),
    val results: List<ArchivedMessage> = emptyList(),
    val apps: List<AppSummary> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
) {
    val hasCriteria: Boolean get() = appliedQuery.isNotBlank() || filters.active
}

class SearchViewModel(
    private val vault: VaultRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {
    /** Text-field state lives in snapshot state (recommended for TextField); the flow below drives the search. */
    var query by mutableStateOf("")
        private set

    private val queryFlow = MutableStateFlow("")
    private val filters = MutableStateFlow(SearchFilters())

    private val debouncedQuery = queryFlow
        .debounce { if (it.isBlank()) 0L else debounceMillis }
        .map { it.trim() }
        .distinctUntilChanged()

    private val results = combine(debouncedQuery, filters) { q, f -> q to f }
        .flatMapLatest { (q, f) ->
            if (q.isBlank() && !f.active) flowOf(q to emptyList()) else vault.observeMessages(toQuery(q, f)).map { q to it }
        }

    private val conversations = filters.map { it.packages }.distinctUntilChanged().flatMapLatest { vault.observeConversations(it) }

    val state: StateFlow<SearchUiState> = combine(results, filters, vault.observeAppSummaries(), conversations) { (q, list), f, apps, convs ->
        SearchUiState(q, f, list, apps, convs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(value: String) {
        query = value
        queryFlow.value = value
    }

    fun togglePackage(packageName: String) = filters.update { f ->
        val packages = if (packageName in f.packages) f.packages - packageName else f.packages + packageName
        // A conversation belongs to exactly one app; drop it if its app is no longer selected.
        val keepConversation = f.conversationId?.takeIf { id ->
            packages.isEmpty() || state.value.conversations.firstOrNull { it.id == id }?.packageName in packages
        }
        f.copy(packages = packages, conversationId = keepConversation)
    }

    fun setConversation(id: Long?) = filters.update { it.copy(conversationId = id) }
    fun setDayRange(range: DayRange?) = filters.update { it.copy(dayRange = range) }
    fun setFavoritesOnly(value: Boolean) = filters.update { it.copy(favoritesOnly = value) }
    fun setPossiblyDeletedOnly(value: Boolean) = filters.update { it.copy(possiblyDeletedOnly = value) }
    fun clearFilters() = filters.update { SearchFilters() }

    internal fun toQuery(text: String, f: SearchFilters): MessageQuery {
        val range = f.dayRange
        return MessageQuery(
            text = text.ifBlank { null },
            packages = f.packages,
            conversationId = f.conversationId,
            fromMillis = range?.let { startOfLocalDay(it.startUtcMillis) },
            toMillis = range?.let { startOfLocalDay(it.endUtcMillis + DAY_MILLIS) - 1 },
            favoritesOnly = f.favoritesOnly,
            possiblyDeletedOnly = f.possiblyDeletedOnly,
            limit = RESULT_LIMIT,
        )
    }

    /** The picker's UTC-midnight millis identify a calendar date; convert it to that date's start in [zone]. */
    private fun startOfLocalDay(utcMidnightMillis: Long): Long =
        Instant.ofEpochMilli(utcMidnightMillis).atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        const val DEBOUNCE_MILLIS = 300L
        const val RESULT_LIMIT = 500
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
