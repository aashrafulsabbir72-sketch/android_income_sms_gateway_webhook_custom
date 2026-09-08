# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A free, open-source Android app (`tech.bogomolov.incomingsmsgateway`) that forwards incoming SMS to a user-configured URL as an HTTP POST with a JSON body. No backend, no accounts. The repo is intentionally a "stable, minimal" build — it is maintained but not actively expanded, so prefer small, focused changes over large refactors.

## Build & test

The project uses Gradle (wrapper committed, Gradle 9.4.1) and the Android plugin (AGP 9.2.1, `compileSdkVersion 33`, `minSdkVersion 14`). Source is Java, not Kotlin. AGP 9.x requires JDK 17 or newer — build with JDK 17 (e.g. `JAVA_HOME=<jdk17> ./gradlew ...`).

```bash
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # build release APK (minify disabled)
./gradlew testDebugUnitTest    # run the JVM unit tests (no device needed)
./gradlew connectedAndroidTest # run the instrumented tests (REQUIRES a connected device/emulator)
```

Most tests live in `app/src/androidTest/` and are **instrumented tests** — they run on a device/emulator, not the local JVM. There are also a few pure-JVM unit tests in `app/src/test/` (e.g. `ForwardingConfigPrepareMessageTest`) that run with `testDebugUnitTest`; prefer adding tests there when the logic under test touches no Android APIs. Run a single instrumented test class with:

```bash
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=tech.bogomolov.incomingsmsgateway.WebhookCallerTest
```

CI runs both suites via GitHub Actions (`.github/workflows/tests.yml`): a fast JVM unit-test job, and an emulator job for the instrumented tests.

When adding a feature, cover it with tests at the same level the codebase already uses: a `SharedPreferences`-backed model gets an instrumented model test (see `FailedMessageTest`, `HeartbeatSettingsTest`), and a screen's form validation gets an Espresso instrumented test driving the views (see `MainActivityTest`, `SettingsActivityTest`). For an Espresso test, don't exercise paths that start the foreground service (e.g. a valid "save" that calls `startForegroundService`) — it leaves a real service/notification running; cover the persistence separately via the model test instead.

`local.properties` is **git-ignored** (not committed); it points to the Android SDK locally, and CI falls back to the runner's `ANDROID_HOME`.

## Architecture

The data flow is: **SMS arrives → matched against configs → a WorkManager job POSTs to the webhook with retries.**

- **`SmsReceiverService`** — a foreground `Service` (the "F" status-bar icon) that keeps the process alive and hosts the heartbeat ping. Since issue #78 it registers **no** SMS receiver: `SmsBroadcastReceiver` is declared in the manifest (`SMS_RECEIVED` is on the implicit-broadcast allowlist, and the entry is guarded by the system-only `BROADCAST_SMS` permission), so the system cold-starts the process to deliver an SMS even after an aggressive OEM battery manager kills the service. Don't add a runtime `registerReceiver` back — that would double-deliver every message. Started by `MainActivity` when at least one config exists, and re-started on device boot by `BootCompletedReceiver`.

- **`SmsBroadcastReceiver.onReceive`** — the core dispatch logic (manifest-declared, see above). Reconstructs the message from PDUs, loads all `ForwardingConfig`s, and for each config checks: sender matches (exact `String.equals`, the asterisk wildcard `*`, or — when the per-rule "sender is a regular expression" flag is on (issue #88) — a regex `find()` that *fails closed* on an invalid pattern), SMS forwarding is enabled, the optional per-rule text filter regex matches the body (issue #52; empty forwards everything, an invalid pattern *fails open*), and SIM slot matches. SIM slot detection is heuristic (`detectSim`) because the bundle key for SIM slot is OEM-specific — it probes many known key names. Matching configs are dispatched via `callWebHook`, which enqueues a `OneTimeWorkRequest` with `NetworkType.CONNECTED` constraint and exponential backoff.

- **`RequestWorker`** (a WorkManager `Worker`) — runs the actual HTTP call off the main thread. Reads config from input `Data`, delegates to `Request`, and maps the result to `Result.success/retry/failure`. Retries stop once `getRunAttemptCount()` exceeds the config's max retries (default 10). WorkManager handles the backoff and waiting-for-network, which is how "retry with exponential backoff" is implemented. When the per-config "store failed messages" option is on, a request that exhausts its retries (or errors permanently) is persisted via `FailedMessage` instead of being silently dropped.

- **`FailedMessage`** — opt-in storage for messages that never delivered (issue #3). Persists the failed payloads in their own `SharedPreferences` file and exposes `getCount()` / `retryAll()` (re-enqueues them through `RequestWorker`). Surfaced in the UI by the "Retry N failed" action-bar item in `MainActivity`.

- **`Request`** — wraps `HttpURLConnection`. Returns one of three string constants: `RESULT_SUCCESS`, `RESULT_RETRY` (non-2xx response or `IOException` → triggers a WorkManager retry), `RESULT_ERROR` (malformed URL, bad headers, SSL setup failure → permanent failure). Supports per-config custom headers (JSON object, string values only), chunked vs fixed-length streaming, and an "ignore SSL" mode (uses `TLSSocketFactory` + allow-all hostname verifier).

- **`ForwardingConfig`** — the model and persistence layer. Each forwarding rule is one `SharedPreferences` entry, stored as a JSON string keyed by a generated `timestamp_random` key. `getAll()` deserializes every entry and is the single source of truth for configs. Note the legacy-format fallback: an entry whose value does not start with `{` is treated as a bare URL string from an old app version, with defaults filled in. When adding a new config field, add a `KEY_*` constant, a getter/setter, include it in `save()`'s JSON, and add a guarded read in `getAll()` (use `json.has(...)` checks so old stored configs still load).

- **`prepareMessage`** — builds the request body by substituting placeholders into the user's template: `%from%`, `%text%`, `%sentStamp%` (SMS-reported send time), `%receivedStamp%` (device receive time) — each optionally formatted as `%sentStamp=<SimpleDateFormat>%` / `%receivedStamp=<SimpleDateFormat>%` (issue #42; device-local timezone, format runs to the first `%`, bad pattern yields empty via `formatStamp`, JSON-escaped string result vs the bare numeric epoch-millis form), `%sim%`, the device-health data points `%version%` / `%battery%` / `%power%` / `%network%` (issue #39, read via the null-context-safe `DeviceInfo` helper using the model's own `Context`; `%battery%` is a bare number `-1`–`100`, the rest are fixed-enum strings, and none needs a new runtime permission — `%network%` uses the already-declared `ACCESS_NETWORK_STATE`), and `%Regex=<pattern>%` (extracts part of the SMS body — first capturing group, else whole match, else empty string; invalid patterns yield empty and never crash; the first *unescaped* `%` ends the pattern). `%text%` and the `%Regex%` result are JSON-escaped via Apache Commons Text; the others are not, so they must sit inside JSON string quotes in the template (or, for the numeric `%battery%`/`%sentStamp%`/`%receivedStamp%`, used bare). Substitution is a single pass over the `PLACEHOLDER` pattern via `Matcher.appendReplacement`, whose replacement argument treats `$`/`\` specially, so every substituted value is wrapped in `Matcher.quoteReplacement` — keep that wrapping when editing, or a sender/SIM containing `$` throws `IndexOutOfBoundsException`; the single pass also means an inserted value that itself looks like a placeholder is never re-expanded.

- **`HeartbeatSettings` / `SettingsActivity`** — global (not per-rule) "is the app still alive" monitor (issue #31). `HeartbeatSettings` persists an enabled flag, URL, and interval in its own `SharedPreferences` file (kept separate from `ForwardingConfig`, whose `getAll()` would otherwise try to parse these entries as configs). The ping itself runs inside `SmsReceiverService` on a dedicated `HandlerThread` rather than WorkManager — WorkManager's periodic minimum is 15 min, whereas a foreground service can ping at sub-15-min intervals through Doze. `SettingsActivity` edits the settings and pokes the service with the `ACTION_RESCHEDULE_HEARTBEAT` intent action to re-read them without a full restart. `SettingsActivity` also hosts backup **export/import** of the forwarding rules (issue #76) via the Storage Access Framework (`startActivityForResult`, not the Activity Result API — the AndroidX deps are too old): `ForwardingConfig.exportToJson()` / `importFromJson()`, which share `fromStoredValue()` with `getAll()` so backups honor the same per-field defaults; import merges by rule key and skips rules missing required fields.

- **UI** — `MainActivity` (list + permission request + Syslog dialog + "Retry N failed" and Settings action-bar items; the rule list is reloaded in `onResume` because rules can change in `SettingsActivity` via import), `ListAdapter` (renders configs, toggles), `ForwardingConfigDialog` (add/edit form; the advanced section is collapsed unless the rule deviates from defaults), `SettingsActivity` (heartbeat settings + backup export/import). The Syslog viewer shells out to `logcat` via `Runtime.exec` filtered to this package.

## Conventions & gotchas

- Bump `versionCode` **and** `versionName` in `app/build.gradle` for any release.
- Templates and config values are stored as raw JSON in SharedPreferences; preserve backward compatibility with old stored entries (see `getAll()` fallback above) — do not assume a field is present.
- `usesCleartextTraffic="true"` and the ignore-SSL path are intentional, to support self-hosted/local endpoints.
- The app keeps deep `minSdkVersion 14` compatibility; much code branches on `Build.VERSION.SDK_INT`. Keep new code working on old APIs or guard it.
- The app is **localized** — strings live in `app/src/main/res/values/strings.xml` (English, the source of truth) and one file per translated locale (e.g. `values-ru/strings.xml`). When you add or rename a string, add it to `values/strings.xml` **and** every `values-*/strings.xml`, or that locale silently falls back to English. If you can't translate, still add the key (English value is acceptable as a placeholder) so the set of keys stays in sync. Store/F-Droid listing text is translated separately under `fastlane/metadata/android/<locale>/`.
- F-Droid distributes this app, and metadata lives in `fastlane/metadata/`.
