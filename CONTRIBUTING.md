# Contributing

Thanks for helping. UnsendVault is a small, single-maintainer project with firm boundaries, so please read this
before opening a pull request.

## Ground rules

- **Offline only.** No `INTERNET` or other network permission, no analytics, ads, crash reporters or SDKs that talk to
  a server. The build fails (`verifyPermissions<Variant>`) if the merged manifest gains a permission outside the
  allowlist; do not extend the allowlist without discussing it in an issue first.
- **Honest capture.** Never mark a message deleted from a notification removal. New deletion markers must come from
  text the source app actually displays, and must match exactly one message (see
  [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md#7-deletion-evidence-rules)).
- **Privacy by default.** New features that reveal message content (widgets, sharing, notifications) are off by
  default and never active while the app lock is on.
- **No real message data** in issues, tests or fixtures. Write synthetic snapshots.
- **Battery.** No polling, foreground services or exact alarms.

## Reporting a capture problem

Include: Android version and device, the messaging app and its version, what the notification showed, what UnsendVault
stored, and whether the chat was open, muted or had hidden previews. Redact names and text.

## Development

- JDK 21, Android SDK platform 37 and build-tools 37.0.0.
- Before pushing:

  ```sh
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPermissionsDebug :app:verifyPermissionsRelease :app:assembleDebug
  ```

- Parsers and adapters live in `app/src/main/java/com/riyadm/unsendvault/capture/` and are tested on the JVM with
  recorded snapshots. A new adapter needs tests for normal messages, groups, summaries, media placeholders and its
  deletion markers.
- Database changes need a new Room schema version, a migration, an exported schema in `app/schemas/` and a
  `MigrationTestHelper` test. Destructive migrations are not accepted.
- Kotlin official code style; keep functions small and Android-free where possible.
- Shell scripts in `scripts/` use `set -euo pipefail` and must pass `bash -n` and `shellcheck`.

## Pull requests

- One topic per pull request, with a description of what changed and how you tested it (unit tests, device, API
  level).
- CI must be green. Every merge to `main` publishes a signed release, so main must always be releasable.
- Add a line to [CHANGELOG.md](CHANGELOG.md) under "Unreleased" for user-visible changes.
- By contributing you agree that your contribution is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)).

## Security

Do not open public issues for vulnerabilities; see [SECURITY.md](SECURITY.md).
