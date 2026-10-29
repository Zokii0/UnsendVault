package com.riyadm.unsendvault.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Subset of GET /repos/{owner}/{repo}/releases/latest; every other field is ignored. */
@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
)

object ReleaseParser {
    const val RELEASES_PAGE_URL = "https://github.com/riyadmondol2006/UnsendVault/releases/latest"
    private const val PAGE_PREFIX = "https://github.com/riyadmondol2006/UnsendVault/releases/"
    private const val DOWNLOAD_PREFIX = "https://github.com/riyadmondol2006/UnsendVault/releases/download/"
    private const val MAX_NOTES_CHARS = 20_000

    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    sealed interface Result {
        data class Release(val release: ReleaseInfo) : Result

        /** Draft, prerelease, or a tag that is not a version. */
        data object NotEligible : Result

        data object Malformed : Result
    }

    fun parse(text: String): Result {
        val dto = try {
            json.decodeFromString(GitHubReleaseDto.serializer(), text)
        } catch (_: SerializationException) {
            return Result.Malformed
        } catch (_: IllegalArgumentException) {
            return Result.Malformed
        }
        if (dto.draft || dto.prerelease) return Result.NotEligible
        val version = VersionComparator.parse(dto.tagName)?.toString() ?: return Result.NotEligible
        val apk = dto.assets.firstOrNull { it.name == "UnsendVault-$version.apk" && isTrusted(it.browserDownloadUrl, DOWNLOAD_PREFIX) }
        val sha = dto.assets.firstOrNull { it.name == "UnsendVault-$version.apk.sha256" && isTrusted(it.browserDownloadUrl, DOWNLOAD_PREFIX) }
        return Result.Release(
            ReleaseInfo(
                version = version,
                tagName = dto.tagName,
                name = dto.name?.takeIf { it.isNotBlank() } ?: "UnsendVault $version",
                pageUrl = dto.htmlUrl?.takeIf { isTrusted(it, PAGE_PREFIX) } ?: RELEASES_PAGE_URL,
                apkUrl = apk?.browserDownloadUrl,
                apkSizeBytes = apk?.size?.takeIf { it > 0 },
                sha256Url = sha?.browserDownloadUrl,
                notes = dto.body.orEmpty().take(MAX_NOTES_CHARS),
                publishedAt = dto.publishedAt,
            ),
        )
    }

    /**
     * Only links into this project's GitHub releases are ever opened, so a tampered or unexpected response cannot send
     * the user's browser elsewhere.
     */
    internal fun isTrusted(url: String, prefix: String): Boolean =
        url.startsWith(prefix) && url.length < 512 && url.none { it.isWhitespace() || it.isISOControl() || it == '\\' } &&
            !url.contains("..") && !url.contains('@')
}
