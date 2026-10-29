package com.riyadm.unsendvault.ui

import app.cash.turbine.test
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.ui.search.DayRange
import com.riyadm.unsendvault.ui.search.SearchFilters
import com.riyadm.unsendvault.ui.search.SearchViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val vault = InMemoryVaultRepository(Fixtures.conversations, Fixtures.messages)

    @Test
    fun noCriteriaShowsNothing() = runTest(main.dispatcher) {
        SearchViewModel(vault, ZoneOffset.UTC).state.testState {
            runCurrent()
            val state = expectMostRecentItem()
            assertFalse(state.hasCriteria)
            assertTrue(state.results.isEmpty())
            assertEquals(2, state.apps.size)
        }
    }

    @Test
    fun queryIsDebounced() = runTest(main.dispatcher) {
        val vm = SearchViewModel(vault, ZoneOffset.UTC)
        vm.state.testState {
            runCurrent()
            vm.onQueryChange("chap")
            assertEquals("chap", vm.query)
            advanceTimeBy(200)
            runCurrent()
            assertEquals("", expectMostRecentItem().appliedQuery)
            advanceTimeBy(150)
            runCurrent()
            val state = expectMostRecentItem()
            assertEquals("chap", state.appliedQuery)
            assertEquals(listOf(4L), state.results.map { it.id })
        }
    }

    @Test
    fun rapidTypingSearchesOnlyFinalQuery() = runTest(main.dispatcher) {
        val vm = SearchViewModel(vault, ZoneOffset.UTC)
        vm.state.testState {
            runCurrent()
            listOf("s", "se", "see").forEach {
                vm.onQueryChange(it)
                advanceTimeBy(100)
            }
            advanceTimeBy(400)
            runCurrent()
            val state = expectMostRecentItem()
            assertEquals("see", state.appliedQuery)
            assertEquals(listOf(2L), state.results.map { it.id })
        }
    }

    @Test
    fun filtersCombine() = runTest(main.dispatcher) {
        val vm = SearchViewModel(vault, ZoneOffset.UTC)
        vm.state.testState {
            runCurrent()
            vm.togglePackage(Fixtures.WHATSAPP)
            runCurrent()
            assertEquals(setOf(1L, 2L, 3L), expectMostRecentItem().results.map { it.id }.toSet())

            vm.setFavoritesOnly(true)
            runCurrent()
            assertEquals(listOf(2L), expectMostRecentItem().results.map { it.id })

            vm.setFavoritesOnly(false)
            vm.setPossiblyDeletedOnly(true)
            runCurrent()
            assertEquals(listOf(3L), expectMostRecentItem().results.map { it.id })

            vm.clearFilters()
            runCurrent()
            assertFalse(expectMostRecentItem().hasCriteria)
        }
    }

    @Test
    fun conversationFilterDroppedWhenItsAppIsDeselected() = runTest(main.dispatcher) {
        val vm = SearchViewModel(vault, ZoneOffset.UTC)
        vm.state.testState {
            runCurrent()
            vm.togglePackage(Fixtures.WHATSAPP)
            runCurrent()
            vm.setConversation(Fixtures.alex.id)
            runCurrent()
            assertEquals(setOf(1L, 2L), expectMostRecentItem().results.map { it.id }.toSet())
            vm.togglePackage(Fixtures.TELEGRAM)
            vm.togglePackage(Fixtures.WHATSAPP)
            runCurrent()
            val state = expectMostRecentItem()
            assertNull(state.filters.conversationId)
            assertEquals(listOf(4L), state.results.map { it.id })
        }
    }

    @Test
    fun dayRangeIsInclusiveInLocalZone() {
        val zone = ZoneId.of("America/New_York")
        val vm = SearchViewModel(vault, zone)
        val start = LocalDate.of(2025, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(2025, 10, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val query = vm.toQuery("", SearchFilters(dayRange = DayRange(start, end)))
        assertEquals(LocalDate.of(2025, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli(), query.fromMillis)
        assertEquals(LocalDate.of(2025, 10, 4).atStartOfDay(zone).toInstant().toEpochMilli() - 1, query.toMillis)
        assertNull(query.text)
    }

    @Test
    fun dayRangeFiltersResults() = runTest(main.dispatcher) {
        val vm = SearchViewModel(vault, ZoneOffset.UTC)
        val day = LocalDate.of(2020, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        vm.state.testState {
            runCurrent()
            vm.setDayRange(DayRange(day, day))
            runCurrent()
            val state = expectMostRecentItem()
            assertTrue(state.hasCriteria)
            assertTrue(state.results.isEmpty())
        }
    }
}
