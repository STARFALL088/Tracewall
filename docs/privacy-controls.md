# Feature 10: child privacy controls

## Integration status on main

The privacy controls are integrated into the shipped child app:

- `PrivacyActivity` is registered `exported="false"` in the child manifest and is
  reached only from Settings after the existing PIN gate. It keeps the explicit
  Back button, ScrollView and rotation scroll state, with matching EN/BN
  `privacy.xml` resources.
- The history clear path is PIN-gated and calls `HistoryWriter.clearStored(...)`.
  A writer/provider failure is reported as a failure, not as a zero-row success.
- The app-name consent button refreshes from the persisted
  `isInventorySharingEnabled()` state, never from an unchecked dialog choice. A
  rejected change (pending cloud deletion) is explained and does not run the
  pairing continuation.
- Pairing and parent management explain when cloud mode is off and never turn on
  cloud mode or either sharing consent implicitly. Pairing works in cloud mode
  with **both sharing consents off**.
- Cloud mode requiring an authenticated identity reports `IDENTITY_REQUIRED`.
  Startup never creates an anonymous identity. Email/password is the only remote
  method; a successful sign-in links to the existing guest/device identity, and
  `IdentityAnchor` rejects an unrelated UID.
- Every privacy change and deletion remains individually PIN-gated. No document
  picker, export or sharing action is added by `PrivacyActivity`.

## Behavior and defaults

- `tracewall.sync/cloud_enabled` defaults to false, including upgrades which had
  no explicit cloud consent. `share_query_history` independently defaults false.
  Existing F8 `share_app_names` remains a separate consent, off by default.
- Local mode stops every Tracewall upload source (DNS history, protection-off
  events, new-app events and app-name snapshots), removes RTDB rules, connection,
  pairing and membership listeners plus Firestore's pairing listener, calls RTDB
  `goOffline()` and Firestore `disableNetwork()`, and does not initiate anonymous
  sign-in. The status distinguishes shutdown requested, disabled and failure.
- The Firebase identity, SDK caches and last valid remote rules are retained for
  reconnect. This is **not** sign-out, parental revocation, a device-wide network
  sandbox, or a promise that no packet can leave during asynchronous shutdown.
  Existing list downloads/WorkManager behavior is intentionally unchanged.
- Cloud mode permits database networking, pairing/remote rules and protection-off
  events. Query-history and app-name consent are independent. Local mode remembers
  those choices but overrides their effective permission. Each new mode/consent
  generation invalidates all older callback work, including off/on ABA changes.
- History authorization is captured at `HistoryWriter.offer()`, not at Room flush.
  Only a still-current positive ticket can upload. No method scans Room or uploads
  stored history when consent is enabled. Old in-memory work may be conservatively
  discarded on a consent change; this is best-effort history, not an audit ledger.
- Metadata scans and async read/commit callbacks carry the same consent generation.
  Actual SDK commit issuance and consent mutation share the facade monitor.
  `LatestWork` allows one active snapshot and only the latest pending request; stop
  replaces pending work with a no-op. Opt-out cleanup waits for an older submitted
  batch to settle and never lets that older callback issue another batch. Cleanup
  is best effort in cloud mode and retries on refresh/reconnect; no immediate or
  server-verified metadata-erasure claim is made by the sharing toggle. Existing
  `new_app` events require the explicit upload deletion control.

## Local history retention and clear race

- `tracewall.privacy/retention_days` accepts only 0/1/3/7, default 7. Invalid stored
  values fall back to 7; the UI/API reject arbitrary windows. Zero disables Room
  inserts and clears existing Room rows. The 500-entry process live view remains.
  Explicit query sharing can still upload with local persistence disabled.
- A retention change immediately invalidates queued/drained work and prunes or
  clears Room on the bounded UI worker. Startup and approximately once-per-minute
  maintenance enforce the stored choice while this process runs. Age depends on
  the device clock; this is not a background TTL guarantee while the app is dead.
- `HistoryBuffer`'s storage monitor covers epoch validation **and the actual
  synchronous Room insert**. Clear acquires that same monitor, advances the epoch,
  discards the bounded queue, then deletes Room rows. An in-flight insert finishes
  before deletion; a previously drained batch fails its epoch check afterwards.
  Packet offers never acquire this storage monitor or wait for disk/Firebase.
- The queue is still capped at 2,000 rows; a flush is at most 256. Cumulative
  `HistoryWriter.droppedRowCount()` is shown in the diagnostics panel together
  with overflow and storage-failure losses. Intentional no-persistence or
  stale-generation discards are not counted as overload. The VPN service now
  consumes these counters for diagnostics; retention/clear semantics are unchanged.
- Local clear affects **only stored Room history and pre-clear writer work**, not
  the live view, cloud data, SDK queues/caches or exported files. New post-clear
  queries can appear immediately. Provider delete delegates to this same clear.
  Failures are surfaced, not presented as a successful clear. Neither Room DELETE
  nor retention is secure forensic disk erasure.

## Explicit cloud upload deletion

The confirmation precisely scopes deletion to this authenticated child's
`devices/{uid}/{history,events,apps,location}` **Firestore** subcollections. No parent can
choose another UID through this API. It does not erase identity, parent-owned
rules, memberships, pairing, legacy RTDB data, exports, other caches or recursively
nested unknown subcollections. Deployed child-only write/delete rules are required;
no security rules are broadened or deployed here.

1. After PIN approval, stop location sharing, turn both share choices off, select local mode, persist a
   pending-deletion flag, invalidate all upload generations and stop listeners and
   pending publisher work. Cloud/sharing cannot be enabled while pending.
2. Leave RTDB offline. Temporarily enable **only Firestore** for this explicit
   action, using the retained identity (never create a new one to pretend to erase
   the old identity's data). Await `waitForPendingWrites()` before querying/deleting.
3. That barrier includes SDK writes from previous app sessions. Previously queued
   uploads **may reach the server before being deleted**. SDK writes already issued
   cannot be recalled by mode changes. The callback/generation gates ensure that
   no old Tracewall producer can add a later write behind the barrier.
4. Read the first remaining document-ID page using `Source.SERVER`, limited to 200
   documents; delete it in a write batch and await its server acknowledgment. Repeat
   on the shrinking collection, then move to the next collection. Bound an attempt
   to 50 batches total and a 120-second main-looper timeout. Larger jobs stay pending
   and resume by an explicit PIN-approved retry; no collection is loaded unboundedly.
5. After all four collections have been processed, read **each again** from the
   server with limit 1. Cached results, pending-write metadata, nonempty results,
   unavailable identity, offline/error/timeout/budget exhaustion are not success.
6. Only after all server read-backs are empty and `disableNetwork()` completes
   successfully is deletion marked confirmed with a timestamp. Stay local with both
   shares off. Failure or process death leaves pending work; restart does **not**
   reconnect for deletion without another PIN-approved retry.

No client can erase copies a parent already read, cached, exported or photographed.
Read-back proves these collections empty at that time, not that another process
using the same anonymous identity can never create data later. SDK caches/pending
operations are deliberately not wiped using `clearPersistence()`; that API is not
secure erasure and has initialization restrictions. Offline pending uploads and
partially completed deletes may be delivered on a later approved reconnect/retry.

API contracts consulted: official Firebase Android `FirebaseFirestore` reference
(`disableNetwork`, `waitForPendingWrites`, `clearPersistence`), RTDB Android offline
capabilities, and Firestore delete-data documentation. The latter recommends server
bulk deletion for large collections; this child-owned, bounded, resumable control
is not an administrative bulk-deletion service.

## Verification in this worktree

Safe demo fixtures were generated by the existing CI generator, which exclusively
creates missing files and refuses to open/overwrite existing configs. No `.env`,
real credentials, production project calls, accounts, billing or deployments used.

Executed successfully on 7 November 2026 with JDK 17 / SDK 34 / AGP 8.5.2 + Gradle 8.9:

```sh
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
ANDROID_HOME="$HOME/Android/Sdk" ./gradlew --no-daemon --no-parallel \
  --max-workers=2 --console=plain --continue :blocklist:test \
  :app:testDebugUnitTest :auth:testDebugUnitTest :parent:testDebugUnitTest \
  :app:assembleDebug :parent:assembleDebug \
  :app:lintDebug :parent:lintDebug :auth:lintDebug
```

- 300 JVM tests, zero failures/errors/skips: 231 core, 11 child Robolectric,
  6 parent and 52 auth. This includes 22 privacy tests (default/independent
  grants, no backfill, generation/ABA invalidation, deletion lockout/bounds and
  server-proof decisions, retention boundaries, bounded overflow, drained
  pre-clear work and coordinated in-flight insert/clear) and 2 diagnostics
  counter tests. Concurrency tests use latches/futures, not sleeps. These are
  **not** Android/Production Firebase runtime tests.
- 22 Python contract tests passed, including the UI/auth contracts.
- Both debug APKs built. Lint passed with zero errors (104 child, 40 parent and
  7 auth warnings; dominated by pre-existing unused legacy resources and
  API-level attributes). Reports: `app/build/reports/lint-results-debug.*`,
  `parent/build/reports/lint-results-debug.*`, `auth/build/reports/lint-results-debug.*`;
  final log: `build/ci/final-verification.log`. The child-only rebuild after the
  always-on string correction also passed (0 errors, 104 warnings); its log is
  `build/ci/always-on-rebuild.log`.
- EN/BN privacy XML has matching keys and matching format placeholders.
  `git diff --check` passed.
- APK SHA-256:
  - `app/build/outputs/apk/debug/app-debug.apk`:
    `3bf8db338fa5c13a7d5c004c78cacd6f4c7fccda63ef80d496ee2090f1a6aeb3`
  - `parent/build/outputs/apk/debug/parent-debug.apk`:
    `ad5063a253631967ea49cecc45adc770e7ee2ce186b9ddb5e9696a37fd5daafd`

Both debug APKs were installed over the existing data on the Samsung SM-A127F
(`R58RA294D7L`, Android 13) and the child app launched in local mode. Home showed
zeroed diagnostics counters and the HTTPS/SVCB probe logging toggle as "on".
After the phone was unlocked, protection started normally and the diagnostics
panel moved to Allowed 7 / Blocked 3 with zero SERVFAIL, DNS queue drops and
history drops. Settings → Privacy controls displayed the parent-PIN prompt, so
the entry point and gate were exercised, but the content behind it was not
opened. Settings also showed the corrected always-on message: Android 13 does
not expose the selected app to third-party apps, so the screen directs the user
to Android VPN settings instead of claiming a verified state.

The PIN-gated privacy choices, retention/deletion behavior, rotation, real SDK
networking shutdown, persistent offline write ordering and server-acknowledged
deletion under interrupted connectivity remain **unverified on Android /
production Firebase**. No PIN was guessed or reset. Demo JSON does not configure
emulators and must not be installed for a meaningful Firebase runtime check. No
production success, security deployment or cache-recall claim is made. Exercise
the PIN-gated screens on an authorized disposable test project/device before
release.
