package com.riyadm.unsendvault.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVisibilityTest {
    @Test
    fun statusCardOnlyWhenCaptureNeedsAttention() {
        assertFalse(showStatusCard(CaptureStatus.ACTIVE))
        assertTrue(showStatusCard(CaptureStatus.NO_ACCESS))
        assertTrue(showStatusCard(CaptureStatus.PAUSED))
        assertTrue(showStatusCard(CaptureStatus.DISCONNECTED))
    }

    @Test
    fun appChipsFromTwoAppsOrWhileAFilterIsActive() {
        assertFalse(showAppFilterChips(HomeTab.RECENT, appCount = 0, selectedPackages = emptySet()))
        assertFalse(showAppFilterChips(HomeTab.RECENT, appCount = 1, selectedPackages = emptySet()))
        assertTrue(showAppFilterChips(HomeTab.RECENT, appCount = 2, selectedPackages = emptySet()))
        // A filter picked in the Apps tab stays visible (and clearable) even with a single app.
        assertTrue(showAppFilterChips(HomeTab.CONVERSATIONS, appCount = 1, selectedPackages = setOf("com.whatsapp")))
        // The Apps tab lists the apps itself.
        assertFalse(showAppFilterChips(HomeTab.APPS, appCount = 3, selectedPackages = setOf("com.whatsapp")))
    }
}
