# UnsendVault product research

Status: research baseline for v1.0, written 2026-10-29. Platform facts are cited; where a statement comes from our own
device testing or is unverified, it says so. Section 5 keeps three kinds of evidence apart: what the official
documentation says, what we observed on a device, and what we have not verified.

## 1. Problem

People on WhatsApp, Instagram, Messenger, Telegram, Signal, Google Messages and similar apps sometimes see a message
notification that is gone once they open the chat: the sender deleted or unsent it, or edited it. Some also want a
private, searchable log of the message previews they have already received, for example to find an address someone
sent while they were driving.

Android lets an app that the user explicitly approves under **Notification Access** read the notifications other apps
post ([NotificationListenerService][nls]). UnsendVault archives the **text those notifications showed**, on the device,
encrypted, and with nothing sent off it.

### Who it is for

| User | Need | What UnsendVault offers |
|---|---|---|
| Someone who missed a message before it was deleted or unsent | See what the notification said | The archived preview, with a "possibly deleted" label only when the source app itself displayed a deletion marker |
| Someone who reads previews on the go | Find a message preview later | Search across apps, conversations, dates |
| Someone who wants a personal record of what they received | Keep it private | Local only, encrypted database, app lock, no network permission |

### Who it is not for

- Anyone who wants to read **another person's** messages. UnsendVault only sees notifications on the phone where it is
  installed, after that phone's owner turns on Notification Access in system settings. It is not a monitoring tool and
  must not be marketed or used as one (section 8).
- Anyone who expects message **recovery**. UnsendVault cannot fetch anything from a server, from a chat database or
  from a backup. It can only keep what a notification showed while capture was running.

## 2. What a notification-archive app can and cannot do

### Can

- Read the fields a notification exposes to listeners: title, text, big text, inbox lines, and for
  [MessagingStyle][ms] notifications the list of messages with sender and timestamp, plus "historic" messages added
  for context ([MessagingStyle reference][ms]).
- Notice when the same notification is updated (a new message arrives, a message is edited, or the app replaces a
  message with "This message was deleted").
- Learn that a notification was removed and why (user dismissed it, the app cancelled it, and so on) through
  `onNotificationRemoved` with a reason ([NotificationListenerService][nls], `REASON_*` constants).

### Cannot

| Not captured | Why |
|---|---|
| Messages sent **before** Notification Access was granted, or while capture was paused, the listener was disconnected, or the phone was off | There was no notification event to read. |
| Chats the user **muted** in the source app, if the app posts nothing for them | No notification. (Some apps post muted chats silently; those are captured, which is why the listener does not filter out silent notifications.) |
| Chats that are **open on screen** when the message arrives | Messaging apps usually do not post a notification for the conversation you are looking at. A third-party report about Android 17 describes exactly this for Google Messages ([PhoneLeash][phoneleash]). |
| **Hidden previews** ("New message", "2 new messages") set in the source app, or by the system for locked apps | The text never reaches the notification. |
| **Media**: photos, videos, voice notes, stickers, files | Notifications carry at most a placeholder ("Photo") or a thumbnail; UnsendVault stores only the placeholder text and never stores images. |
| Long messages beyond what the app put in the notification | Apps often truncate; the archive keeps what was shown. |
| Content the **system withholds** from untrusted listeners | Android 15+ redacts notifications where an OTP was detected ([Android 15 behavior changes][a15]); Android 17 adds restrictions around end-to-end encrypted messages whose effect on listeners is not documented (section 5). |
| Notifications in a **work profile** | "The system also ignores notification listeners running in a work profile" ([NotificationListenerService][nls]). |
| Anything on **low-RAM devices running Android 10 and below** | "Notification listeners cannot get notification access or be bound by the system on low-RAM devices running Android Q (and below)" ([NotificationListenerService][nls]). |
| Proof that a message was deleted | A notification disappearing is not evidence; see section 4. |

## 3. Landscape (described generically)

We describe categories rather than rate specific products, because claims about other apps' internals cannot be
verified from the outside.

| Category | What is publicly observable | Gap UnsendVault aims at |
|---|---|---|
| **Android's built-in notification history** (Android 11+, Settings > Notifications > Notification history) | Off by default on many devices; AOSP documents a default retention of 24 hours ([AOSP notification history][aosp-history]). Not searchable across days, no per-conversation view. | Long retention, conversation threading, search, update history. |
| **"Deleted message recovery" apps** using notification access | Many store listings in this category show ads and request network access. Their listing text sometimes implies recovery of any deleted message, which notification access cannot provide. | Honest scope, no network permission at all, no ads, open source. |
| **General notification loggers / automation tools** | Capture every app's notifications as raw text. | Messaging-specific parsing (MessagingStyle threads, senders, groups, dedupe), messages-only default. |
| **Chat apps' own features** (for example, an "edited" label or keeping deleted messages for the sender) | Vary per app and change over time. | Works across apps; read-only; never touches the chat app. |

Positioning: an **open-source, offline-only, encrypted** archive of message previews, with clear limits stated up
front, no INTERNET permission (enforced at build time and verified on the release APK), and no data collection.

## 4. Deletion and edit semantics (product rules)

These rules exist because false "deleted" labels would mislead users about what other people did.

- **Possibly deleted / unsent** is set only when the source app itself displayed an explicit marker such as "This
  message was deleted" or "unsent a message", and the marker can be matched to exactly one archived message: an exact
  (sender, MessagingStyle timestamp) match, or a single candidate from that sender in the same conversation within the
  previous 15 minutes. The marker text and the time it was seen are stored and shown.
- **Notification removal is never deletion evidence.** People dismiss notifications, open chats, read on another
  device; apps cancel notifications when a message is read. The removal reason is recorded only for transparency.
- **Edits / updates**: when a message with the same identity reappears with different text, the new text becomes
  current and the previous text is kept in the message's update history. The UI says "updated", not "edited", because
  some apps rewrite previews for other reasons (for example, a media placeholder being replaced).
- The label is "possibly" deleted: the app cannot see the server, and marker wording differs between app versions and
  languages.

## 5. Platform research

### 5.1 NotificationListenerService basics (official)

From the [NotificationListenerService reference][nls]:

- The service must be declared with `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"` and the
  `android.service.notification.NotificationListenerService` intent filter. The user enables it in system settings;
  it is not a runtime permission dialog.
- "The service should wait for the `onListenerConnected()` event before performing any operations. The
  `requestRebind(ComponentName)` method is the only one that is safe to call before `onListenerConnected()` or after
  `onListenerDisconnected()`."
- After `onListenerDisconnected()`: "You will not receive any events after this call, and may only call
  `requestRebind(ComponentName)` at this time."
- "From `Build.VERSION_CODES.N` onward all callbacks are called on the main thread." This is why UnsendVault's
  callback only copies fields and hands off to a background consumer.
- `META_DATA_DISABLED_FILTER_TYPES` (`android.service.notification.disabled_filter_types`, API 31): a list of types
  (`ongoing`, `conversations`, `alerting`, `silent`) the listener never wants; they appear as off and disabled in the
  settings UI. UnsendVault declares `ongoing` only and keeps `silent`, because muted chats can post silently.
- `META_DATA_DEFAULT_AUTOBIND` (API 34): a listener can opt out of automatic binding. UnsendVault keeps the default
  (bound automatically).
- `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30) opens the access page for one component, with
  `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` ([Settings reference][settings]); older versions use
  `ACTION_NOTIFICATION_LISTENER_SETTINGS` (API 22).
- API 36 and 37 added methods for creating and deleting conversation channels for other packages; they require an
  associated companion device or the notification-assistant role and are irrelevant to UnsendVault.

From the [MessagingStyle reference][ms]:

- MessagingStyle is the template for "multiple back-and-forth messages of varying types between any number of
  people"; apps targeting API 28+ should use `Person` for senders and `setGroupConversation(boolean)` for groups.
- `MAXIMUM_RETAINED_MESSAGES = 25`: at most 25 messages are kept in the notification itself. A running listener sees
  every update, so this only matters for messages that arrived while the listener was disconnected.
- `addHistoricMessage` (API 26) adds context messages "if they are not the main subject of the notification". The
  parser treats them as lower-priority input: they can fill gaps but do not override current messages.
- From Android 14, MessagingStyle notifications linked to a valid conversation shortcut (`setShortcutId`) appear in
  the conversation section. UnsendVault uses `shortcutId` as the most stable conversation key when present.

### 5.2 Android 13+: "restricted settings" for sideloaded apps

UnsendVault is distributed as an APK from GitHub Releases. On Android 13 and later, Android may block granting
Notification Access to an app installed outside an app store until the user allows it under App info > More menu >
**Allow restricted settings** ([Google help: restricted settings][restricted]; [XDA][xda-restricted] describes the
notification-listener case and the session-based installer exemption; [9to5Google][9to5-a15] reports that the Android
15 CDD widens the list of restricted settings). Onboarding must explain this step, with the exact menu path, before
sending the user to the Notification Access screen. Whether the block triggers depends on how the APK was installed;
we have not tested every browser and file manager.

### 5.3 Android 15 (API 35): OTP redaction and RECEIVE_SENSITIVE_NOTIFICATIONS

**Official documentation** ([Android 15 behavior changes: all apps][a15], section "OTP Redaction"):

> Android will stop untrusted apps that implement a NotificationListenerService from reading unredacted content from
> notifications where an OTP has been detected. Trusted apps such as companion device manager associations are exempt
> from these restrictions.

The same section notes that notification content is hidden during screen sharing.

**AOSP source.** The exemption is the permission `android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS`, documented in
`frameworks/base/core/res/AndroidManifest.xml` as "Allows apps with a NotificationListenerService to receive
notifications with sensitive information". At the `android-17.0.0_r1` tag its protection level is
`signature|preinstalled|knownSigner|role` (older definition: `signature|role`) ([AOSP manifest at
android-17.0.0_r1][aosp-manifest-17]). A third-party app installed from GitHub cannot obtain it.

**Secondary reports.** Android Authority tested that a listener could read 2FA codes on Android 14 but not on
Android 15, with detection done by Android System Intelligence ([Android Authority][aa-otp]). A KDE Connect bug report
shows the placeholder "Sensitive notification content hidden" after upgrading to Android 15 ([KDE bug 495146][kde]).

**Consequences for UnsendVault**

- Messages that the system classifies as containing an OTP arrive redacted. UnsendVault stores them as
  `SYSTEM_REDACTED` with a neutral explanation instead of the placeholder text, so the archive does not look broken.
- UnsendVault's own OTP filter is still useful: it covers Android 8 to 14, and codes the system classifier misses.
- UnsendVault will not guide users to grant the permission through adb or app-ops workarounds. That would weaken a
  platform protection for one app's convenience.

### 5.4 Android 17 (API 37): message access restrictions

**Official documentation**

- The [Android 17 features and changes list][a17-summary] contains a Privacy item, marked "Change (all apps)":
  **"Restricted message access — Most apps now cannot access end-to-end encrypted messages."** The item links to
  `behavior-changes-all#restricted-message-access`.
- That anchor does **not** exist on the [Behavior changes: all apps][a17-all] page as of 2026-10-29. The page's
  Privacy section covers only SMS OTP protection (a 3-hour delay for WebOTP-format SMS for apps that are not the
  intended recipient). Neither it nor [Behavior changes: apps targeting Android 17][a17-target] nor the
  [Android 17 release notes][a17-rn] nor the [Android 17 launch post][a17-blog] mentions NotificationListenerService,
  notification content or a "message read restriction".
- [Behavior changes: apps targeting Android 17][a17-target] extends the OTP delay to standard SMS for apps targeting
  API 37. This affects apps that read SMS through `SMS_RECEIVED_ACTION` or the SMS provider. UnsendVault holds no SMS
  permission and is not affected by this item directly.
- The same page deprecates `ContentCaptureManager.setContentCaptureEnabled(false)` for apps targeting 37 and points
  to `FLAG_SECURE` instead. UnsendVault's "secure screen" option already uses `FLAG_SECURE`.
- The public AOSP `config.xml` at tag `android-17.0.0_r1` has no resource named
  `config_messaging_read_restriction_allowlist` ([AOSP config.xml at android-17.0.0_r1][aosp-config-17]). The setting
  may have been added after that tag or may live outside the public tree; we could not find it.

**Third-party reports (not official)**

- A users' forum thread notes the missing documentation and asks what the restriction covers; nobody answered
  ([Privacy Guides forum][pg]).
- An SMS backup vendor says that after the upgrade to Android 17, end-to-end encrypted RCS messages are marked
  "restricted" in Android's messaging database and only the default SMS app can read them, and that Google confirmed
  this is intentional ([SyncTech FAQ][synctech]).
- A forwarding app's blog says Android 17 restricts RCS reading to the default messaging app, and that no
  notification is posted when the conversation is open on screen ([PhoneLeash][phoneleash]).

**What we observed on a device** (Pixel 6 "oriole", Android 17 / API 37, build CP3A.261005.002.A1)

- A static framework overlay package, `com.google.android.overlay.messagingreadrestrictionallowlist`, sets the
  framework resource `config_messaging_read_restriction_allowlist` to
  `["com.google.android.apps.pixel.relationships", "com.google.android.contacts"]`.
- Among messaging apps only Google Messages was installed on that device (no WhatsApp, Instagram or Messenger).
- **Google Messages SMS previews were readable.** When UnsendVault's listener connected, its catch-up pass archived
  the text of two SMS notifications from Google Messages already in the shade, in full. So on this build the
  restriction did not withhold plain SMS notification text from a third-party listener targeting API 37. Whether
  it withholds end-to-end encrypted RCS, or other apps' messages, could not be tested (no RCS conversation and no
  third-party messenger on the device).
- **Android's OTP redaction did not trigger in our test.** With `cmd notification redact_otp_from_untrusted_listeners
  true`, a MessagingStyle notification posted by `com.android.shell` with the text "Your verification code is
  551207. Do not share it." reached UnsendVault's listener unredacted. The platform's detection may depend on the
  posting app, the notification assistant's classification, or timing; either way it cannot be relied on, which
  is why UnsendVault's own OTP filter is on by default (it caught the same text when enabled).
- Full capture results from that device are recorded in [TEST_REPORT.md](TEST_REPORT.md).

**Not verified**

- Whether the "messaging read restriction" covers **notification content delivered to NotificationListenerService**,
  or only the SMS/RCS provider. The resource name and the allowlisted packages (a Google contacts app and a Pixel
  relationships app) suggest a gate on reading message data, possibly including notification content, with an
  allowlist of privileged readers, but this is inference.
- Whether it applies to third-party end-to-end encrypted apps (WhatsApp, Signal, Messenger), to RCS only, or
  differs between Pixel and other OEM builds.
- Whether it depends on the reading app's target SDK. UnsendVault targets API 37, so it gets the strictest behavior
  either way.

**How UnsendVault handles the uncertainty**

- The capture pipeline treats a missing or placeholder body as `SYSTEM_REDACTED` and never invents content.
- The status screen should explain on Android 17+ that some apps' end-to-end encrypted messages may not be readable
  by apps like UnsendVault, and links to this document.
- The lead's device test results (docs/TEST_REPORT.md) are the authority for what works on the reference device. This
  section is updated when official documentation appears.

### 5.5 Background execution and OEM restrictions

- A notification listener is bound by the system while access is granted; it does not need a foreground service or
  exact alarms. UnsendVault uses neither (the manifest removes WorkManager's `SystemForegroundService`).
- Some manufacturers' power managers stop or freeze background components more aggressively than AOSP. The
  community-maintained [Don't kill my app!][dkma] ranking puts several large vendors at the worst end and AOSP-based
  devices (Pixel, Android One) at the best end. Symptoms for a listener: it silently stops receiving callbacks until
  the app is opened or access is toggled.
- Mitigations available without extra permissions:
  - call `NotificationListenerService.requestRebind(ComponentName)` when the app starts or the user opens the status
    screen and the listener is not connected ([NotificationListenerService][nls]);
  - show a "listener disconnected" state in the app and the capture widget;
  - open the battery-optimization list (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, no permission needed)
    and show per-vendor guidance. UnsendVault does **not** use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: it needs
    the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which is outside the app's permission allowlist, and the
    platform documentation says "most applications should not use this" ([Settings reference][settings]).

### 5.6 Play policy considerations

UnsendVault is not distributed on Google Play. If it ever were, the [User Data policy][play-userdata] would apply:
an in-app prominent disclosure "within the app itself, not only in the app description", shown "in the normal usage
of the app", with consent "granted by the user before your app can begin to collect or access the personal and
sensitive user data". UnsendVault's onboarding already follows that shape (explanation screen, then the system
settings page). The User Data policy page does not mention NotificationListenerService specifically; we did not find
a Play declaration form for it. Apps whose listing promises recovery of all deleted messages would risk the
deceptive-behavior rules, which is another reason the listing and README state the limits first.

## 6. Privacy and ethics

- **Consent**: only the phone's owner can turn on Notification Access, in system settings, after an in-app
  explanation. The app never asks for it on first launch without context.
- **Other people's messages**: the archive contains messages other people sent. They chose to send them to the user,
  and the user's phone displayed them, but senders did not agree to long-term storage, and some deleted them on
  purpose. UnsendVault therefore keeps everything local, offers retention (default 30 days), per-app exclusion, a
  messages-only default, and makes deletion easy (single message, conversation, app, everything). The README asks
  users to respect senders' choices and local law.
- **No surveillance use**: the app cannot be installed or enabled remotely, hides nothing from the phone's owner,
  shows a launcher icon, and its capture state is visible in the app and widget.
- **Local only**: no INTERNET permission, enforced by the build and checked on every release APK. No analytics, ads,
  crash reporters or trackers. Backups are user-initiated encrypted files through the system file picker.
- **Sensitive content**: one-time codes and other sensitive patterns are replaced before storage by default, and the
  system's own redaction (section 5.3) is respected.

## 7. Requirements summary (from this research)

| ID | Requirement |
|---|---|
| R1 | Explain limits before access is requested; explain Android 13+ "Allow restricted settings" for sideloaded installs. |
| R2 | Never mark a message deleted without an explicit marker from the source app matched to exactly one message. |
| R3 | Keep a revision history for updated messages. |
| R4 | Store system-redacted content as such; never store the placeholder as if it were the message. |
| R5 | No INTERNET permission; local encrypted storage; app lock; FLAG_SECURE option. |
| R6 | Detect disconnected listener; offer `requestRebind`, battery-settings guidance; no foreground service. |
| R7 | Retention with favorites kept; easy bulk deletion; encrypted export as the only backup path. |
| R8 | Widgets show counts only by default; content only when enabled and the app lock is off. |

## 8. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Android restricts listener access to message content further (Android 17 restriction already listed, details unknown) | High | High | Treat content as optional; `SYSTEM_REDACTED` state; status screen explains; track docs; device tests per release. |
| 2 | Source apps change notification format or deletion-marker wording | High | Medium | Per-app adapters with unit tests on recorded snapshots; generic MessagingStyle fallback; never infer deletion from removal. |
| 3 | False "deleted" label harms a relationship | Medium | High | Strict matching rule (section 4), "possibly" wording, marker text shown as evidence. |
| 4 | OEM kills the listener; user believes capture is running | Medium | High | Connected state in app and widget; rebind; vendor guidance. |
| 5 | Sideload restriction blocks onboarding | High on 13+ | Medium | Step-by-step "Allow restricted settings" screen. |
| 6 | Lost DB key (Keystore cleared, device reset, restore to new phone) | Low | High | Explain that backups are only via encrypted export; backup reminder option. |
| 7 | Encrypted export password forgotten | Medium | Medium | Clear warning; no recovery by design. |
| 8 | Misuse as a monitoring tool | Low | High | Visible app, local-only, owner-granted access, README stance; no remote features. |
| 9 | A dependency adds INTERNET or another permission | Medium | High | `verifyPermissions<Variant>` in every build, `verify-release.sh` on the APK. |
| 10 | Release key loss or leak | Low | High | Secrets only in release steps; offline backup of the key; documented in RELEASING.md. |
| 11 | Storage growth | Low | Low | Retention job, text only, no media. |
| 12 | Legal differences between countries on storing communications | Medium | Medium | Local-only design; user responsibility stated in README and PRIVACY.md. |

## Sources

All accessed 2026-10-29. "Secondary" marks reports that are not official platform documentation.

- [Android Developers: NotificationListenerService reference][nls]
- [Android Developers: Notification.MessagingStyle reference][ms]
- [Android Developers: Settings reference][settings]
- [Android Developers: Android 15 behavior changes, all apps][a15]
- [Android Developers: Android 17 features and changes list][a17-summary]
- [Android Developers: Android 17 behavior changes, all apps][a17-all]
- [Android Developers: Android 17 behavior changes, apps targeting Android 17][a17-target]
- [Android Developers: Android 17 release notes][a17-rn]
- [Android Developers Blog: Android 17 launch post (June 2026)][a17-blog]
- [AOSP frameworks/base AndroidManifest.xml at android-17.0.0_r1][aosp-manifest-17]
- [AOSP frameworks/base config.xml at android-17.0.0_r1][aosp-config-17]
- [AOSP: Notification history][aosp-history]
- [Google Android Help: Learn about restricted settings][restricted]
- [Google Play Console Help: User Data policy][play-userdata]
- [Android Authority: Android 15 hides 2FA codes from notification listeners (secondary)][aa-otp]
- [KDE Bugzilla 495146: KDE Connect and Android 15 OTP notifications (secondary)][kde]
- [XDA Developers: Android 13 restricted setting for notification listeners (secondary)][xda-restricted]
- [9to5Google: Android 15 sideloaded app restrictions (secondary)][9to5-a15]
- [Privacy Guides forum: Android 17 privacy and security features thread (secondary)][pg]
- [SyncTech FAQ: Why are some messages missing from the backup? (secondary)][synctech]
- [PhoneLeash blog: Android 17 and RCS forwarding (secondary)][phoneleash]
- [Don't kill my app! (community-maintained)][dkma]

[nls]: https://developer.android.com/reference/android/service/notification/NotificationListenerService
[ms]: https://developer.android.com/reference/android/app/Notification.MessagingStyle
[settings]: https://developer.android.com/reference/android/provider/Settings
[a15]: https://developer.android.com/about/versions/15/behavior-changes-all
[a17-summary]: https://developer.android.com/about/versions/17/summary
[a17-all]: https://developer.android.com/about/versions/17/behavior-changes-all
[a17-target]: https://developer.android.com/about/versions/17/behavior-changes-17
[a17-rn]: https://developer.android.com/about/versions/17/release-notes
[a17-blog]: https://android-developers.googleblog.com/2026/06/Android-17.html
[aosp-manifest-17]: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/res/AndroidManifest.xml
[aosp-config-17]: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/res/res/values/config.xml
[aosp-history]: https://source.android.com/docs/core/display/notification-history
[restricted]: https://support.google.com/android/answer/12623953
[play-userdata]: https://support.google.com/googleplay/android-developer/answer/10144311
[aa-otp]: https://www.androidauthority.com/android-15-two-factor-authentication-codes-3492585/
[kde]: https://bugs.kde.org/show_bug.cgi?id=495146
[xda-restricted]: https://www.xda-developers.com/android-13-restricted-setting-notification-listener/
[9to5-a15]: https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/
[pg]: https://discuss.privacyguides.net/t/android-17-launched-what-new-privacy-security-features-does-it-bring/38585
[synctech]: https://www.synctech.com.au/faqs/why-are-some-messages-missing-from-the-backup/
[phoneleash]: https://phone-leash.com/blog/android-17-rcs-forwarding
[dkma]: https://dontkillmyapp.com/
