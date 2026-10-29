# Security policy

UnsendVault holds other people's messages, so two things count as security issues here: **anything that exposes the
archive** to someone other than the phone's owner, and **anything that makes the app go online or capture more than
it says**.

## Reporting a vulnerability

Please report privately, not in a public issue:

1. Open the repository's **Security** tab and choose **Report a vulnerability**
   ([direct link](https://github.com/riyadmondol2006/UnsendVault/security/advisories/new)).
2. Include the Android version, device, UnsendVault version (Settings > About) and steps to reproduce.

Please do not include real message content from other people; use test messages.

UnsendVault is maintained by one person. You should get a first reply within about a week. Once a fix is released the
advisory is published, and you are credited if you wish.

## In scope

- Another app (without root) reading the archive, settings, the database key, or export files the app wrote.
- Exported components, intents, widgets or deep links that reveal message content or change settings without the
  owner's action.
- App lock bypasses: reaching archive content while the lock should be shown, or widgets showing content while the
  lock is on.
- Message content leaking into logs, Recents thumbnails (with secure screen on) or the system clipboard without the
  user's action.
- Weaknesses in the export format: decryption without the password, undetected tampering, crashes or resource
  exhaustion from a crafted import file.
- A release APK that declares `INTERNET` or any permission outside the documented allowlist, or that is signed by a
  different certificate than the one published in the release notes.
- Messages marked "possibly deleted" without the source app's marker (this misleads users about other people).

## Out of scope

- Content Android withholds from notification listeners, apps that post no notification, or capture gaps caused by a
  phone maker's battery management. These are limitations or reliability bugs; please file normal issues.
- Attacks needing root, a modified system, an unlocked bootloader with a malicious image, or physical access to an
  unlocked phone with the app lock off.
- Weak export passwords chosen by the user.

## What the archive encryption protects

Message text, sender names, conversation titles and keys, edit history and deletion markers are encrypted per field
(AES-256-GCM) with keys derived from a secret that only the Android Keystore can unwrap. This protects that content
if the database file is copied off the phone (for example a forensic or root-level file copy) without being able to
run code as UnsendVault. It does **not** protect against code running as the app, or a rooted attacker who uses the
Keystore key while the phone is unlocked. App names, package names, timestamps, flags and message counts are stored
without app-level encryption; Android's file-based encryption still protects all app storage at rest. Reports that
show content readable from a copied database file, or ciphertext that can be moved between rows or columns without
detection, are in scope.

## Supported versions

Only the [latest release](https://github.com/riyadmondol2006/UnsendVault/releases/latest) receives fixes.

## Verifying what you install

Every release states the APK's SHA-256 and the signing certificate's SHA-256 and includes a `.sha256` file. See
[README.md](README.md#verify-the-download) and [docs/RELEASING.md](docs/RELEASING.md). The security design is
described in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md#9-security-model).
