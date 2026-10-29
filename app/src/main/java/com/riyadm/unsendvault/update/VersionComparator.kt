package com.riyadm.unsendvault.update

/** Numeric major.minor.patch. Suffixes ("-debug", "+build") are ignored. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

object VersionComparator {
    // "1.0.15", "v1.0.15", "1.0.15-debug", "V2.1"; missing minor/patch count as 0.
    private val pattern = Regex("""^[vV]?(\d{1,9})(?:\.(\d{1,9}))?(?:\.(\d{1,9}))?(?:[-+][0-9A-Za-z.\-+]*)?$""")

    fun parse(raw: String?): AppVersion? {
        val match = raw?.trim()?.let(pattern::matchEntire) ?: return null
        val (major, minor, patch) = match.destructured
        return AppVersion(major.toInt(), minor.ifEmpty { "0" }.toInt(), patch.ifEmpty { "0" }.toInt())
    }

    /** True only when both parse and [candidate] is strictly greater than [current]. */
    fun isNewer(candidate: String?, current: String?): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        return a > b
    }
}
