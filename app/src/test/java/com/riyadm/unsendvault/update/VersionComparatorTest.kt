package com.riyadm.unsendvault.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun parsesPlainPrefixedAndSuffixedVersions() {
        assertEquals(AppVersion(1, 0, 15), VersionComparator.parse("1.0.15"))
        assertEquals(AppVersion(1, 0, 15), VersionComparator.parse("v1.0.15"))
        assertEquals(AppVersion(1, 0, 15), VersionComparator.parse("V1.0.15"))
        assertEquals(AppVersion(1, 0, 15), VersionComparator.parse("1.0.15-debug"))
        assertEquals(AppVersion(1, 0, 15), VersionComparator.parse(" 1.0.15+42 "))
        assertEquals(AppVersion(2, 1, 0), VersionComparator.parse("2.1"))
        assertEquals(AppVersion(3, 0, 0), VersionComparator.parse("v3"))
    }

    @Test
    fun rejectsGarbage() {
        listOf(null, "", "latest", "1..2", "1.0.15.4", "x1.0.0", "1.0.15 debug", "1.a.3", "99999999999.0.0").forEach {
            assertNull(it, VersionComparator.parse(it))
        }
    }

    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(VersionComparator.isNewer("1.0.10", "1.0.9"))
        assertTrue(VersionComparator.isNewer("v1.0.16", "1.0.15-debug"))
        assertTrue(VersionComparator.isNewer("1.1.0", "1.0.99"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.9.200"))
        assertFalse(VersionComparator.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun newerOnlyWhenStrictlyGreater() {
        assertFalse(VersionComparator.isNewer("1.0.15", "1.0.15"))
        assertFalse(VersionComparator.isNewer("v1.0.15", "1.0.15-debug"))
        assertFalse(VersionComparator.isNewer("1.0.14", "1.0.15"))
    }

    @Test
    fun unparseableIsNeverNewer() {
        assertFalse(VersionComparator.isNewer("nightly", "1.0.0"))
        assertFalse(VersionComparator.isNewer("9.9.9", "local-build"))
        assertFalse(VersionComparator.isNewer(null, "1.0.0"))
    }
}
