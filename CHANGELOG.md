# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Releases are built and published automatically from `main` and numbered `MAJOR.MINOR.N`, where `N` is the commit count
(see [docs/RELEASING.md](docs/RELEASING.md)); each GitHub release also lists the commits it contains.

## Unreleased (1.0)

First version, in development.

### Added
- Capture of message notifications through Notification Access: MessagingStyle, inbox and big-text notifications,
  with adapters for common messaging apps and a generic fallback
- Encrypted archive (Room on SQLCipher, key protected by the Android Keystore) with conversations, full-text search,
  favorites and update history
- "Possibly deleted" label only when the source app displayed an explicit deletion marker matched to exactly one
  message; notification removals recorded for transparency only
- Pause/resume, excluded apps, messages-only mode, one-time-code and sensitive-number filter
- Auto-delete with a daily background job (favorites kept; 0 = keep forever)
- App lock (biometrics or screen lock), relock timeout, secure screen option
- Password-encrypted export and import
- Five Glance widgets (archive count, recent, favorites, app summary, capture control), counts and app names only by default,
  refreshed only on change
- No INTERNET permission, enforced at build time and checked on every release APK
- CI: unit tests, lint, permission guard, signed GitHub Releases with SHA-256 checksums and signing-certificate
  fingerprint
