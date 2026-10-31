# Battery and background behaviour

UnsendVault's notification listener is bound by the system around the clock while Notification Access is granted.
Every notification on the phone, from every app, reaches its callbacks. That path accounts for almost all of the
app's battery cost, so this document starts with it. It covers:

1. what runs when (the background model),
2. the optimizations and why each one is safe,
3. a measurement protocol for a real device, and
4. recommendations for the data layer.

## 1. Background model

| Trigger | What runs | Thread | Touches |
|---|---|---|---|
| Any notification posted (any app) | `CapturePreFilter`: package, flags, category, settings snapshot, then the template string and the *presence* of `EXTRA_MESSAGES` / `EXTRA_HISTORIC_MESSAGES`. Most notifications stop here. | main (listener callback) | memory only |
| … that passes the pre-filter | `SnapshotFactory` copies strings and primitives into a `NotificationSnapshot`, which is put in the coalescing queue (keyed by notification key) | main | memory only |
| … 500 ms after the first queued update for that key | single consumer: settings (in memory), adapter parse, sensitive filter, identical-content check, then at most **one** `ingest` transaction | `Dispatchers.Default.limitedParallelism(1)` | vault DB (opened lazily on the first real ingest) |
| Notification removed | the same pre-filter on the removed notification; the removal is forwarded only if the notification could have been archived, or if its key was archived or is still queued in this process | main, then the consumer | one `UPDATE … WHERE last_notification_key = ?` |
| Listener connected | settings snapshot starts loading; if capture is paused → `requestUnbind()`; otherwise a catch-up pass (throttled and incremental) feeds the low-priority lane of the same queue | main | `getActiveNotifications()` |
| Listener disconnected unexpectedly | `requestRebind()` with backoff 0 s / 10 s / 1 min / 5 min / 30 min. The ladder resets after a connection that stayed up for 10 min. Not done for a self-requested unbind (pause). | main `Handler` (no alarm) | binder call |
| Capture paused / resumed (app, widget, backup import) | pause → `requestUnbind()`, so the app receives **no** callbacks at all; resume → static `requestRebind()` | settings collector | binder call |
| A widget is placed | `WidgetSyncEngine` observes only the placed kinds. It reduces each source to a display fingerprint and marks a kind dirty only when the fingerprint differs from the last *rendered* one. It flushes after 1.5 s of quiet, at most 5 s after the first change, and only while the screen is on. | `appScope` | Room flows for placed kinds; Glance session (WorkManager) per render |
| No widget placed | nothing is observed. The sync engine is not even started at process start (a placed widget starts it through its receiver). | n/a | 5 `getAppWidgetIds` binder calls at process start |
| Daily | `RetentionWorker`. Unique periodic job, constraints battery-not-low + device-idle. | WorkManager | vault DB |
| Process start (the listener cold-starts the process after a reboot or a kill) | `Application.onCreate` builds the lazy container and starts one background placement check. No DataStore, DB or WorkManager work happens here, and Compose is not touched. | main + 1 background coroutine | nothing persistent |
| API 35+, 15 s after process start | the widget-preview publication check: one SharedPreferences read; publishes once per app version | `appScope` | prefs |
| UI | only while visible: `collectAsStateWithLifecycle` everywhere, `WhileSubscribed(5 s)` view-model state, no infinite animations | main | as needed |

The app never uses its own wake locks, alarms (exact or inexact), foreground services, polling loops or network. The
only periodic work is the daily retention job. WorkManager holds its own short wake lock while a job runs, which
means a Glance render session or the retention job.

## 2. Optimizations (opt/battery)

### 2.1 Listener hot path: drop early, allocate nothing

`capture/CapturePreFilter.kt` and `SnapshotFactory.preFilter`

- The pre-filter runs in two steps. The header step reads package, flags, category and the settings snapshot; it
  needs no Bundle access. The content step reads the template string and uses `containsKey` for the message arrays,
  so the value is never deserialized. A big-picture promo, a download progress update, a call or an empty group
  summary is rejected without building a snapshot, without queueing anything and without waking the consumer
  thread.
- **Correctness contract.** The pre-filter rejects only what `DefaultCaptureEngine.process` would also reject, so
  capture results are unchanged:
  - own package and ongoing/FGS: the engine gate rejects these;
  - call and system categories, and group summaries without MessagingStyle extras: every parser rejects these, and
    the generic fallback rejects them too. Summary-only apps that put messages in the summary still pass.
  - paused, excluded package and the messages-only gate: the engine's settings gates reject these, so the
    pre-filter applies them **only when the settings snapshot is loaded**.

  `CapturePreFilterTest.preFilterIsConsistentWithTheEngine` checks this over a matrix of 48 notification shapes and
  4 settings combinations.
- The messages-only gate uses `AppAdapter.mayBeMessage(category, channelId, template, hasMessages)`. This is the
  same logic as `isMessageNotification`, which now delegates to it. Adapters that only override
  `isMessageNotification` get the conservative default (`true`).
- The settings snapshot (`CaptureSettingsCache`) is one DataStore collector per process. Until DataStore's first
  read completes the snapshot is `null`: the pre-filter then passes everything through, and the consumer waits for
  the real value via `first()`, as it did before. So an excluded package is never captured during a cold start
  (`CoalescingEngineTest.excludedPackageIsNeverCapturedEvenBeforeSettingsLoad`). The consumer no longer starts a
  DataStore read per item.
- **Removals.** Previously, dismissing *any* notification on the phone queued work and ran an `UPDATE` on the vault,
  which opened the encrypted DB on the first dismissal after every process start. Now a removal is forwarded only
  if:
  - the pre-filter accepts the removed notification, or
  - the content check rejects it but the engine archived or queued that key in this process (`isTracked`).
- `SnapshotFactory` was already strings-only: no bitmaps, RemoteViews or PendingIntents are kept. It now:
  - stops converting inbox lines after 100 (same result as before);
  - allocates a second snapshot only in the rare redacted case;
  - keeps reading `EXTRA_HISTORIC_MESSAGES`, but only for accepted notifications, because the parser archives them.

### 2.2 Coalescing bursts

`capture/engine/CoalescingQueue.kt` and `DefaultCaptureEngine`

- Typing indicators, "N new messages" counters and apps that re-post the same conversation several times a second
  all produce one queue entry per notification key. The latest snapshot wins. The entry is processed 500 ms after
  the *first* update, so a steady stream is still handled every 500 ms and is never starved. Result: one parse and
  at most one ingest transaction per burst.
- The identical-content skip (fingerprint per key) is unchanged. Together with `IngestResult`, this means no write
  happens when nothing changed.
- Removals flush the pending post for the same key, and earlier pending posts of the same package, before
  themselves. A message and its deletion marker are never reordered.
- Catch-up items use a 2 s lane, so live notifications go first.
- The queue is bounded (256). On overflow the oldest pending post is dropped, as with the previous
  `Channel(DROP_OLDEST)`.
- The consumer suspends while the queue is empty. Its only timer is the coalescing window: a coroutine timeout, not
  an alarm, so it never wakes the device.

### 2.3 Catch-up and rebind

`capture/ListenerPolicies.kt`

- **Catch-up:**
  - The first connect in a process does a full pass.
  - A later connect is incremental: only notifications whose `postTime` is no older than 10 s before the last
    disconnect.
  - At most one `getActiveNotifications()` walk every 2 min. A rebind storm is *deferred* to the end of the interval
    with a main-looper message, not skipped, so nothing posted while disconnected is lost.
  - No catch-up while paused.
- **Rebind:** at most one rebind is pending per process, with exponential backoff. There is no rebind after a
  self-requested unbind or while paused. The previous code requested a rebind on every disconnect. Unless the
  listener was in the system's "unbound on request" state, that call is a no-op, but it would have undone a pause
  unbind and caused a bind/unbind loop.

### 2.4 Pause stops the callbacks

`CapturePauseController`, kill switch `UNBIND_WHILE_PAUSED`

- When paused, the listener calls `requestUnbind()`, so the system stops delivering notifications altogether. Pause
  then costs about zero instead of one pre-filter call per notification.
- Resuming from the app, the widget or a backup import calls the static `requestRebind()`, which works while
  unbound. Every path that changes settings goes through `container.settingsRepository`, and creating it starts the
  collector that drives this.
- The system's unbind request is not persistent. After a reboot the system binds the listener again; the
  controller sees "paused" and unbinds again. This happens once, with no loop.
- Safety net: whenever capture is enabled, access is granted and the listener is not connected, a rebind is
  requested. In the system this is a no-op unless the listener is in the unbound state. So clearing app data while
  paused cannot leave the listener stuck.
- The status shown in the UI and widget is unchanged. PAUSED takes precedence over DISCONNECTED in
  `captureState` and `captureStatusOf`. After a resume, the status shows DISCONNECTED briefly until the system
  rebinds.

### 2.5 Process start

- `UnsendVaultApp.onCreate` used to start widget sync and the retention watcher. The retention watcher opened
  DataStore and initialized WorkManager on every cold start, and that WorkManager initialization ran
  `ForceStopRunnable` (opening WorkManager's DB) plus an `enqueueUniquePeriodicWork(UPDATE)` write.
- Now:
  - **WorkManager is initialized on demand.** `UnsendVaultApp` implements `Configuration.Provider` and the
    `WorkManagerInitializer` startup entry is removed in the manifest. WorkManager starts only when a widget renders
    or the retention job runs (SystemJobService initializes it on demand).
  - **The retention watcher starts when the UI starts** (`MainActivity`). Retention settings can only change from
    the UI or a backup import, which also runs in the UI. The periodic job persists in WorkManager and JobScheduler
    across process deaths and reboots.
  - **Widget sync starts only if a widget is placed.** The check uses the receiver class *names*, so Glance is not
    class-loaded. A widget placed later starts sync from its receiver.
- The container was already lazy. Capture now builds the adapters and engine once (`container.capture`). The vault
  DB is opened only by the first real ingest or forwarded removal.

### 2.6 Widgets

- **Render suppression.** `WidgetSyncEngine` remembers the fingerprint it last *rendered* per kind and calls
  `updateAll` only when the current one differs. This covers:
  - a change that is reverted within the debounce window;
  - a re-subscription after a placement change;
  - a privacy-mode switch that produces identical output.

  Explicit `requestRefresh` calls always render.
- **Screen-off gate** (`ScreenOnGate`). While the screen is off the dirty set accumulates. The engine renders once on
  `ACTION_SCREEN_ON`, using a dynamically registered receiver that exists only while something is waiting. A busy
  night or a Doze window therefore costs no Glance sessions and no WorkManager jobs. Rendering never writes the
  observed tables, so a render cannot re-trigger itself.

### 2.7 UI

The only background UI work was a `viewModelScope` collector in `HomeViewModel`. It kept a Room observer on the app
summaries for as long as the view model lived, including while the activity was stopped in the back stack, so every
ingest re-ran that query. That collector is now an `onEach` on the `WhileSubscribed` state. Everything else already
used `collectAsStateWithLifecycle`, and there are no infinite animations.

### 2.8 Debug instrumentation

- **Counters** (debug builds only; in release builds each increment is a skipped branch). Read them with:

  ```
  adb shell dumpsys activity service $COMP
  ```

  `$COMP` is defined in section 3.

  The output covers callbacks, every pre-filter reject reason, snapshots built, removals forwarded and dropped,
  coalesced and dropped queue items, items processed, ingests, identical skips, catch-up runs, deferrals and items,
  rebinds, and unbinds for pause. It contains counts only, never content.
- **Trace sections** (all builds; no-ops unless tracing is on):
  - `UV:onPosted`: listener main thread, per notification;
  - `UV:parse`: consumer, accepted notifications only;
  - `UV:catchUp`.

## 3. On-device measurement protocol (Pixel 6, Android 17)

Compare **A** = `main` (5d46915) and **B** = `opt/battery` with the same build type. Use debug builds of both to read
B's counters, then repeat the battery numbers with release builds if needed. Both must be installed over each other
with the same signing key, so that Notification Access survives the reinstall. Otherwise re-grant it with the
`allow_listener` command below.

Debug builds use the applicationId `com.riyadm.unsendvault.debug`; release builds use `com.riyadm.unsendvault`. The
listener class name does not change. Set these first:

```
PKG=com.riyadm.unsendvault.debug            # or com.riyadm.unsendvault for release builds
COMP=$PKG/com.riyadm.unsendvault.capture.VaultNotificationListener
adb shell cmd notification allow_listener $COMP
```

### 3.1 Setup (each run)

```
adb shell dumpsys battery unplug                 # batterystats only accounts while "unplugged"
adb shell settings put global stay_on_while_plugged_in 0
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats --enable full-wake-history
PID=$(adb shell pidof $PKG); adb shell cat /proc/$PID/stat | awk '{print "utime="$14" stime="$15}'   # CPU ticks before
```

### 3.2 Workloads (run on the device, 10 min each, screen off: `adb shell input keyevent 223`)

Notifications posted by `com.android.shell`, which has no adapter and so goes through the generic path:

```
# W1 noise: non-message notifications (promo/progress-like). B should build 0 snapshots and never open the vault.
adb shell 'for i in $(seq 1 600); do cmd notification post -S bigtext -t "Sale $i" noise_$((i%20)) "50% off today" >/dev/null; sleep 1; done'

# W2 messages: one MessagingStyle conversation, 1 msg/s.
adb shell 'for i in $(seq 1 600); do cmd notification post -S messaging --conversation Bench --message "Alex:msg $i" bench_chat x >/dev/null; sleep 1; done'

# W3 bursts: the same conversation re-posted 10x within ~0.5 s, every 10 s (typing/re-post storms).
adb shell 'for b in $(seq 1 60); do for k in $(seq 1 10); do cmd notification post -S messaging --conversation Burst --message "Alex:b$b" burst x >/dev/null; done; sleep 10; done'
```

Run each workload twice: once with no widget placed, and once with a Recent widget plus a Capture widget on the home
screen. For the widget runs, do one pass with the screen on and one with it off.

Pause run: pause capture in the app, then run W2 for 10 min.

Rebind-storm run: repeat 20 times:

```
adb shell cmd notification disallow_listener $COMP; sleep 2
adb shell cmd notification allow_listener $COMP; sleep 3
```

Cold-start run:

```
adb shell run-as $PKG kill -9 $(adb shell pidof $PKG)
```

Wait for the system to restart the listener process, then post one W2 message.

### 3.3 Collect and compare

| What | Command | Expect B vs A |
|---|---|---|
| App CPU time | `/proc/$PID/stat` utime+stime delta (same PID; note a restart), and `adb shell dumpsys batterystats $PKG` → `Uid … Total cpu time` | W1: large drop (no snapshots, queue items or consumer wake-ups). W2: similar or lower. W3: fewer parses (A parses every re-post and then skips it as identical). |
| Callbacks vs work (B, debug) | `adb shell dumpsys activity service $COMP` | W1: `posted_callbacks≈600`, `prefilter_not_a_message≈600`, `snapshots_built=0`, `ingests=0`. W3: `items_processed` well below `snapshots_built` (`queue_coalesced` > 0; each `cmd` call takes ~100–200 ms, so expect a few items per burst rather than one), `ingests≈60`. Pause: `posted_callbacks` stays flat. |
| Wake locks | `dumpsys batterystats $PKG` → `Wake lock` lines; `adb shell dumpsys power \| grep -i unsendvault` | none of ours; only `WorkManager: …` / `*job*/…` while a widget renders (screen on) |
| Alarms | `adb shell dumpsys alarm \| grep -i unsendvault` | none, in both A and B |
| Jobs | `adb shell dumpsys jobscheduler $PKG` (or `dumpsys jobscheduler \| grep -A4 $PKG`) → completed/active jobs (`SystemJobService`) | No widgets: 0 (B, A may show WorkManager init churn). Widgets + screen off: B ≈ 0 until screen on, then 1 per kind. Retention: one periodic job, constraints `BATTERY_NOT_LOW DEVICE_IDLE`. |
| Process starts | `dumpsys batterystats $PKG` → `Proc $PKG: … N starts`; `adb logcat -b events -d \| grep am_proc_start.*unsendvault` | same count. B's start does less work: compare `bindApplication` duration in a Perfetto trace (below). |
| Cold-start I/O | `adb shell run-as $PKG ls -la --time-style=full-iso databases/ no_backup/` before and after the cold-start run | B: `androidx.work.workdb*` and `files/datastore/settings.preferences_pb` untouched by a W1-only cold start. The vault DB is untouched until a message is archived. |
| Pause | `adb shell dumpsys notification \| grep -B2 -A6 -i unsendvault` | B: our component is absent from the live/bound listeners while paused, and reappears on resume (also resume from the widget). |
| Rebind storm | counters `catch_up_runs`, `catch_up_deferred`, `rebind_requests` | B: `catch_up_runs` ≤ 1 per 2 min; no growing rebind loop |
| Battery | `adb shell dumpsys batterystats --checkin > b.txt`, or Battery Historian on a `adb bugreport`; on Android 17 also `dumpsys batterystats $PKG` → `Estimated power use` / `Computed drain` per UID | B ≤ A in every workload |

Perfetto, for main-thread cost per notification and process-start cost:

```
adb shell perfetto -o /data/misc/perfetto-traces/uv.pftrace -t 60s -a $PKG sched freq am wm view dalvik
adb pull /data/misc/perfetto-traces/uv.pftrace
```

Open the trace in ui.perfetto.dev and compare:

- `UV:onPosted` slices on the main thread: count and duration, rejected vs accepted;
- `UV:parse` on the consumer thread;
- `bindApplication` duration on a cold start.

After all runs:

```
adb shell dumpsys battery reset
```

### 3.4 Risks to watch on device

- **Unbind while paused** depends on `requestUnbind()` / `requestRebind()` behaviour on Android 17. Check:
  - resume from the widget with the app process dead;
  - reboot while paused (the listener should connect and immediately unbind);
  - clearing app data while paused (the listener should rebind on the next app start).

  If anything misbehaves, set `CapturePauseController.UNBIND_WHILE_PAUSED = false`. Pause then falls back to dropping
  callbacks in the pre-filter.
- **On-demand WorkManager.** Check that widgets still render after a reboot, and that the retention job still exists
  in `dumpsys jobscheduler` after reboot and after an app update. After a force-stop, WorkManager reschedules its jobs
  when it next initializes, which happens when the app is next opened or a widget renders.
- **Screen-off gate.** Widgets on the lock screen or hub (if the device shows them) update when the screen turns on,
  not while it is off.
- **Removal filter.** A notification that was archived and then updated to a non-message form in a *previous*
  process no longer records its removal reason. This only affects the transparency field.

## 4. Data-layer recommendations

The data layer was not changed on this branch; these are for the owner of `data/`.

1. **Field encryption and Keystore.**
   - Never use a Keystore-backed key per field operation: every call is a binder round trip to keystore2.
   - Instead, unwrap one data key per process, lazily, on the first ingest or read. Keep it in memory and do AES-GCM
     in-process with `javax.crypto`.
   - Avoid any per-open key derivation (PBKDF2): it would run on every listener cold start that archives something.
2. **`recordRemoval`.** It runs a write transaction even when no row matches, and when a row does match it
   invalidates `messages`. That re-runs every observer (Home, widgets, stats) for a field that only the detail
   screen shows. Suggestions:
   - check `EXISTS` first, or keep removal info in a separate table that only the detail screen observes;
   - skip the write when the reason is unchanged.
3. **FTS triggers.** Room's external-content FTS4 triggers fire on *any* `UPDATE` of `messages`, so favorite toggles,
   status changes and removal reasons all rewrite the FTS row. Restrict them to `UPDATE OF text, sender`. This needs
   a migration with custom triggers.
4. **`recomputeAggregates`.** It runs `COUNT(*)`, `MAX` and `ORDER BY … LIMIT 1` per touched conversation on every
   ingest. Maintain the aggregates incrementally on insert (count + 1, last_* if newer) and keep the full recompute
   for delete paths.
5. **`observeStats`.** It runs 7 subqueries, including `COUNT(DISTINCT package_name)` and `MIN`/`MAX` over all
   messages, and with a widget placed it re-runs on every ingest. Two options:
   - a single maintained stats row, or
   - `first_captured_at` indexes plus a cached `apps` count.

   `observeAppSummaries` (`GROUP BY` over all messages) would also benefit from a per-app aggregate table.
6. **Unchanged re-ingest after a cold start.** The engine's identical-content fingerprints live in memory, so the
   first catch-up after a reboot re-ingests everything in the shade, as N write transactions that report
   "unchanged". Options:
   - persist a small `notification_key → content hash` table and check it in a read transaction;
   - start `ingest` with a read-only check and open the write transaction only when something differs.
7. **WAL.** Keep WAL (Room's default). After SQLCipher is gone, make sure `synchronous=NORMAL` is in effect with WAL
   (one fsync less per commit). Run `PRAGMA wal_checkpoint(TRUNCATE)` and `PRAGMA optimize` from the
   idle/battery-not-low retention worker, not per write.
8. **Retention scheduling.** `startSync` calls `enqueueUniquePeriodicWork(UPDATE)` for the first emission in every
   process that creates the scheduler, which means every UI start. That is a WorkManager DB write plus a
   JobScheduler reschedule. Persist the last scheduled `retentionDays`, or compare against `getWorkInfosForUniqueWork`,
   and skip when unchanged.
9. **`SettingsRepository.current`.** It still starts with defaults (`stateIn(Eagerly, VaultSettings())`). The capture
   layer now uses its own nullable snapshot. Consider exposing `StateFlow<VaultSettings?>` so no caller can act on
   defaults during a cold start.
