package com.riyadm.unsendvault.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app declares INTERNET only for the opt-in update check. This guards that promise at the source level:
 *  - networking APIs may appear only inside the `update` package;
 *  - the capture/listener path, widgets, the data layer and Application startup never reference the update package,
 *    so a notification arriving (or a widget refresh, or a worker) can never trigger a network request.
 */
class NetworkIsolationTest {
    private val sourceRoot: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val appRoot = File(sourceRoot, "com/riyadm/unsendvault")
    private val updatePackage = File(appRoot, "update")

    private val networkTokens = listOf(
        Regex("""java\.net\.URL\b"""),
        Regex("""\bHttpURLConnection\b"""),
        Regex("""\bHttpsURLConnection\b"""),
        Regex("""Socket"""),
        Regex("""\bopenConnection\b"""),
        Regex("""\bURL\("""),
        Regex("""okhttp""", RegexOption.IGNORE_CASE),
        Regex("""\bjava\.net\.(http|Inet|Proxy|URI\b|URLConnection)"""),
        Regex("""javax\.net"""),
    )

    private fun kotlinAndJavaSources(root: File) =
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    private fun File.isInside(dir: File) = canonicalPath.startsWith(dir.canonicalPath + File.separator)

    @Test
    fun sourcesAreFound() {
        assertTrue(kotlinAndJavaSources(appRoot).size > 50)
        assertTrue(File(updatePackage, "GitHubReleaseClient.kt").isFile)
    }

    @Test
    fun networkApisAppearOnlyInTheUpdatePackage() {
        val offenders = kotlinAndJavaSources(sourceRoot)
            .filterNot { it.isInside(updatePackage) }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    networkTokens.firstOrNull { it.containsMatchIn(line) }?.let { "${file.relativeTo(sourceRoot)}:${i + 1}: ${line.trim()}" }
                }
            }
        assertTrue("Network APIs outside the update package:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun theUpdatePackageReallyUsesTheTokensWeScanFor() {
        // Proves the scanner would catch the client if it were moved elsewhere.
        val client = File(updatePackage, "GitHubReleaseClient.kt").readText()
        assertTrue(networkTokens.any { it.containsMatchIn(client) })
    }

    @Test
    fun captureWidgetsDataAndStartupNeverReferenceTheUpdatePackage() {
        val guarded = listOf("capture", "widget", "data", "core", "security").map { File(appRoot, it) } +
            listOf(File(appRoot, "UnsendVaultApp.kt"))
        val reference = Regex("""unsendvault\.update\b|\bupdateController\b|\bUpdateController\b|\bUpdateChecker\b|\bGitHubReleaseClient\b""")
        val offenders = guarded.flatMap { if (it.isDirectory) kotlinAndJavaSources(it) else listOf(it) }
            .filter { reference.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).path }
        assertTrue("These must not reference the update check: $offenders", offenders.isEmpty())
    }

    @Test
    fun theProductionEndpointIsTheOnlyHostInTheClient() {
        val hosts = Regex("""https?://([A-Za-z0-9.\-]+)""").findAll(
            kotlinAndJavaSources(updatePackage).joinToString("\n") { it.readText() },
        ).map { it.groupValues[1] }.toSet()
        assertTrue("unexpected hosts: $hosts", hosts.all { it == "api.github.com" || it == "github.com" })
    }
}
