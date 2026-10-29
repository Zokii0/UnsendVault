package com.riyadm.unsendvault.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryUpdateStore(initial: UpdatePrefs = UpdatePrefs()) : UpdateStore {
    val state = MutableStateFlow(initial)
    override val prefs: Flow<UpdatePrefs> = state

    override suspend fun edit(transform: (UpdatePrefs) -> UpdatePrefs) = state.update(transform)
}

/** Serves the queued results in order; the last one repeats until another is enqueued. Counts requests. */
class FakeReleaseSource(vararg results: FetchResult) : ReleaseSource {
    private val queue = ArrayDeque(results.toList())
    private var headServed = false
    var calls = 0
        private set

    fun enqueue(result: FetchResult) = queue.addLast(result)

    override suspend fun fetchLatest(): FetchResult {
        calls++
        if (headServed && queue.size > 1) queue.removeFirst()
        headServed = true
        return queue.first()
    }
}

fun release(version: String) = ReleaseInfo(
    version = version,
    tagName = "v$version",
    name = "UnsendVault $version",
    pageUrl = "https://github.com/riyadmondol2006/UnsendVault/releases/tag/v$version",
    apkUrl = "https://github.com/riyadmondol2006/UnsendVault/releases/download/v$version/UnsendVault-$version.apk",
    sha256Url = "https://github.com/riyadmondol2006/UnsendVault/releases/download/v$version/UnsendVault-$version.apk.sha256",
    notes = "## What changed\n\n- Something new",
)

const val HOUR = 60L * 60 * 1000
