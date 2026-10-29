# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Releases are built and published automatically from `main` and numbered `MAJOR.MINOR.N`, where `N` is the commit count
(see [docs/RELEASING.md](docs/RELEASING.md)); each GitHub release also lists the commits it contains.

## Unreleased

### Changed
- Storage encryption: SQLCipher is no longer used (about 1 MB smaller download). The archive is a regular Room/SQLite
  database in which message text, sender names, conversation titles and keys, edit history, deletion markers and the
  latest-message preview are each encrypted with AES-256-GCM, using keys derived (HKDF-SHA256) from the same
  Keystore-protected secret as before. Lookups that need equality use keyed HMAC blind indexes instead of plaintext.
  App names, package names, timestamps, flags, status and message counts are not encrypted by the app; Android's
  file-based encryption still protects all app storage at rest.
- Search no longer uses a full-text index (it would have stored the words in plaintext). It decrypts and matches
  messages newest first, after the app/conversation/date/favorite filters; matching is case-insensitive and ignores
  accents, and each word may match the message, the sender or the conversation title.
- Database schema version 3 (`vault-v3.db`), with a migration from a plaintext version 2 database.

### Removed
- SQLCipher for Android and its native library.

### Upgrade note
- **The archive from version 1.0.15 cannot be read by this version** (it is a SQLCipher file). On first start it is
  deleted and a new, empty archive is created with the same key; a one-time notice explains this. If you exported a
  backup with 1.0.15, import it after updating: backups use an unchanged format and import normally.

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
