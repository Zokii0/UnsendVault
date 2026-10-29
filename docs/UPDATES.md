# Update check

UnsendVault can check GitHub for a newer release. The check is **opt-in**: it is off until you allow it, and it is the
only thing in the app that uses the network. Your archive never leaves the device.

## What happens

1. After onboarding, the main screen asks once: *Check for updates automatically?* **Allow** turns the automatic check
   on, **Not now** (or Back) leaves it off. You can change this at any time in **Settings > Updates**.
2. With the automatic check on, the app makes at most one request a day, and only while you are using it: the first
   time the unlocked main screen appears in a process, if 24 hours have passed since the last attempt (6 hours if the
   last attempt failed). Within that window the result of the last check is reused instead of asking GitHub again.
3. If a newer version exists, a dialog shows the new version, your version and a short plain-text excerpt of the
   release notes, with four actions:
   - **Download** opens the APK link in your browser. The browser downloads it and you install it yourself; the app never
     downloads or installs anything.
   - **Release page** opens the GitHub release page.
   - **Later** hides the dialog until the app process restarts.
   - **Skip this version** stops the automatic check from offering that version again (a newer one is still offered).
4. **Settings > Updates > Check now** asks GitHub once, right away, whatever the automatic setting is: pressing the
   button is consent for that one request. It ignores the daily window and a skipped version (you asked), works in
   debug builds, and shows the result inline: up to date, a new version (tap to see it), couldn't reach GitHub, or
   GitHub's rate limit was reached. The section also shows the installed version and when the last check happened.

Debug builds never run the automatic check (the consent dialog and Check now still work, for testing).

## The request

Exactly one anonymous HTTPS request, to a URL fixed in the code
(`GitHubReleaseClient.LATEST_RELEASE_URL`):

```http
GET /repos/riyadmondol2006/UnsendVault/releases/latest HTTP/1.1
Host: api.github.com
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
User-Agent: UnsendVault/1.0.15
```

Android's HTTP stack may add transport headers such as `Accept-Encoding: gzip` and `Connection`. Nothing else is
sent: no query string, no request body, no cookies, no authentication, no device or installation identifiers, no list of
installed apps, and nothing from the archive or settings.

The client (`app/src/main/java/com/riyadm/unsendvault/update/GitHubReleaseClient.kt`):

- uses `HttpsURLConnection` on `Dispatchers.IO`; no third-party HTTP library;
- refuses anything but HTTPS; the manifest sets `usesCleartextTraffic="false"` and
  `res/xml/network_security_config.xml` forbids cleartext and trusts only the system certificate authorities;
- has 10 s connect and read timeouts and an overall 30 s limit while reading;
- reads at most 512 KB of response (a real response is about 13 KB);
- turns every outcome into a typed result: release found, no release (404, or the latest is a draft or prerelease),
  rate limited (429, or 403 with `X-RateLimit-Remaining: 0` or `Retry-After`), other HTTP error, offline, timeout,
  too large, unexpected response. It never throws into the UI;
- parses the JSON with kotlinx.serialization, ignoring unknown fields;
- only accepts links into `https://github.com/riyadmondol2006/UnsendVault/releases/`. The APK link is the asset named
  exactly `UnsendVault-<version>.apk`; the `.apk.sha256` asset and the release page link are also kept.

Release builds log nothing about the check. Debug builds log one short line per result, never the response body.

## What GitHub can see

The same as for any website visit: your IP address, the time of the request, and the `User-Agent`, which contains the
installed UnsendVault version. GitHub's privacy statement applies to that request. If you open **Download** or **Release
page**, your browser visits github.com as it would for any link.

## What is stored on the device

A separate Preferences DataStore file, `update_check`, holds: your choice (not asked / allowed / declined), the time of
the last attempt and whether it failed, the skipped version, and the last release seen (version, links, release
notes). It is excluded from Android backup like the rest of the app's data.

## Verifying a download

Each release page lists the APK's SHA-256 and the signing certificate's SHA-256, and has an
`UnsendVault-<version>.apk.sha256` file:

```sh
sha256sum --check UnsendVault-1.0.16.apk.sha256
apksigner verify --print-certs UnsendVault-1.0.16.apk   # certificate SHA-256 must match the release notes
```

Android also refuses to install an update over the existing app unless it is signed with the same key.

## Why there is no background check

The check only runs from the visible, unlocked main screen. There is no WorkManager job, alarm, service, broadcast
receiver or polling loop for it. The notification listener, widgets, workers and Application startup never touch the
update code. `NetworkIsolationTest` enforces this: it fails the build if network APIs appear outside the `update` package
or if capture, widget, data, core, security or `UnsendVaultApp` code references it. So:

- archiving a notification can never cause a network request;
- if you never open the app, it never contacts anyone;
- battery and data use are one small request a day at most, and only while you are using the app.

## Build and release checks

- `verifyPermissions<Variant>` allows `INTERNET` (update check only) and still fails on `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE` or any other permission outside the allowlist. `ACCESS_NETWORK_STATE`, which WorkManager would
  add, stays removed in the manifest.
- `scripts/verify-release.sh` applies the same rule to the signed APK.
