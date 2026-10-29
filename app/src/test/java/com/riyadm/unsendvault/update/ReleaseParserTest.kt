package com.riyadm.unsendvault.update

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a trimmed copy of the real GET /repos/riyadmondol2006/UnsendVault/releases/latest response (v1.0.15). */
class ReleaseParserTest {
    private val fixture: String = checkNotNull(javaClass.classLoader?.getResource("update/github_release_latest.json")).readText()

    private fun parse(text: String): ReleaseInfo {
        val result = ReleaseParser.parse(text)
        assertTrue("expected a release, got $result", result is ReleaseParser.Result.Release)
        return (result as ReleaseParser.Result.Release).release
    }

    private fun modified(transform: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit): String {
        val obj = ReleaseParser.json.parseToJsonElement(fixture).jsonObject.toMutableMap()
        obj.transform()
        return JsonObject(obj).toString()
    }

    @Test
    fun parsesRealFixture_andPicksApkAndChecksumAssets() {
        val release = parse(fixture)
        assertEquals("1.0.15", release.version)
        assertEquals("v1.0.15", release.tagName)
        assertEquals("UnsendVault 1.0.15", release.name)
        assertEquals("https://github.com/riyadmondol2006/UnsendVault/releases/tag/v1.0.15", release.pageUrl)
        assertEquals("https://github.com/riyadmondol2006/UnsendVault/releases/download/v1.0.15/UnsendVault-1.0.15.apk", release.apkUrl)
        assertEquals(
            "https://github.com/riyadmondol2006/UnsendVault/releases/download/v1.0.15/UnsendVault-1.0.15.apk.sha256",
            release.sha256Url,
        )
        assertEquals(11_511_758L, release.apkSizeBytes)
        assertEquals("2026-10-29T17:35:06Z", release.publishedAt)
        assertTrue(release.notes.startsWith("## Install"))
    }

    @Test
    fun draftAndPrereleaseAreIgnored() {
        assertEquals(ReleaseParser.Result.NotEligible, ReleaseParser.parse(modified { put("draft", JsonPrimitive(true)) }))
        assertEquals(ReleaseParser.Result.NotEligible, ReleaseParser.parse(modified { put("prerelease", JsonPrimitive(true)) }))
    }

    @Test
    fun nonVersionTagIsIgnored() {
        assertEquals(ReleaseParser.Result.NotEligible, ReleaseParser.parse(modified { put("tag_name", JsonPrimitive("nightly")) }))
    }

    @Test
    fun malformedBodies() {
        assertEquals(ReleaseParser.Result.Malformed, ReleaseParser.parse("<html>rate limited</html>"))
        assertEquals(ReleaseParser.Result.Malformed, ReleaseParser.parse("""{"message":"Not Found"}"""))
        assertEquals(ReleaseParser.Result.Malformed, ReleaseParser.parse("[]"))
        assertEquals(ReleaseParser.Result.Malformed, ReleaseParser.parse(""))
    }

    @Test
    fun minimalReleaseWithNullsAndNoAssets() {
        val release = parse("""{"tag_name":"v2.0.1","name":null,"html_url":null,"body":null,"draft":false,"prerelease":false,"assets":[]}""")
        assertEquals("2.0.1", release.version)
        assertEquals("UnsendVault 2.0.1", release.name)
        assertEquals(ReleaseParser.RELEASES_PAGE_URL, release.pageUrl)
        assertNull(release.apkUrl)
        assertNull(release.sha256Url)
        assertEquals("", release.notes)
    }

    @Test
    fun assetSelectionUsesTheExactVersionedName() {
        val base = "https://github.com/riyadmondol2006/UnsendVault/releases/download/v1.2.3/"
        val json = buildJsonObject {
            put("tag_name", "v1.2.3")
            put("html_url", "https://github.com/riyadmondol2006/UnsendVault/releases/tag/v1.2.3")
            put(
                "assets",
                buildJsonArray {
                    listOf("UnsendVault-1.2.2.apk", "mapping-1.2.3.zip", "UnsendVault-1.2.3.apk.sha256", "UnsendVault-1.2.3.apk", "SHA256SUMS.txt")
                        .forEach { name -> add(buildJsonObject { put("name", name); put("browser_download_url", base + name); put("size", 10) }) }
                },
            )
        }.toString()
        val release = parse(json)
        assertEquals(base + "UnsendVault-1.2.3.apk", release.apkUrl)
        assertEquals(base + "UnsendVault-1.2.3.apk.sha256", release.sha256Url)
    }

    @Test
    fun linksOutsideTheProjectReleasesAreDropped() {
        val evil = modified {
            put("html_url", JsonPrimitive("https://evil.example/releases/tag/v1.0.15"))
            val assets = getValue("assets").jsonArray.map { asset ->
                JsonObject(asset.jsonObject + ("browser_download_url" to JsonPrimitive("https://evil.example/UnsendVault-1.0.15.apk")))
            }
            put("assets", kotlinx.serialization.json.JsonArray(assets))
        }
        val release = parse(evil)
        assertEquals(ReleaseParser.RELEASES_PAGE_URL, release.pageUrl)
        assertNull(release.apkUrl)
        assertNull(release.sha256Url)
    }

    @Test
    fun trustedUrlCheck() {
        val prefix = "https://github.com/riyadmondol2006/UnsendVault/releases/download/"
        assertTrue(ReleaseParser.isTrusted(prefix + "v1.0.15/UnsendVault-1.0.15.apk", prefix))
        listOf(
            "http://github.com/riyadmondol2006/UnsendVault/releases/download/v1/a.apk",
            prefix + "../../../other/a.apk",
            prefix + "v1/a b.apk",
            "https://github.com/riyadmondol2006/UnsendVault/releases/download@evil.example/a.apk",
            "https://github.com.evil.example/riyadmondol2006/UnsendVault/releases/download/a.apk",
        ).forEach { assertTrue(it, !ReleaseParser.isTrusted(it, prefix)) }
    }

    @Test
    fun releaseInfoRoundTripsThroughTheCacheFormat() {
        val release = parse(fixture)
        val encoded = ReleaseParser.json.encodeToString(ReleaseInfo.serializer(), release)
        assertEquals(release, ReleaseParser.json.decodeFromString(ReleaseInfo.serializer(), encoded))
    }
}
