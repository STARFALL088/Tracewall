# Post-v1.1.0 update ledger

Baseline: annotated tag `v1.1.0`, commit `93defce`. Work branch:
`updates/post-v1.1.0`. Existing historical tags and `main` are preserved.
Each completed implementation is tested and committed separately, then pushed.

## Acceptance checklist

1. [x] VPN foreground lifecycle: API-compatible foreground notification and cleanup.
2. [x] Empty remote snapshots clear persisted remote rules, preserving local rules.
3. [x] Pairing: recover partial membership, confirm revocation, reconnect existing parents.
4. [x] Remote edits: validate domains, show write results, avoid concurrent lost edits.
5. [x] Core: bounded DNS workers, safe parser slices, locale-independent matching, regressions.
6. [x] Verification: deliberate lint fixes and reproducible GitHub CI.
7. [x] History: persistent filtered browsing, statistics, pagination/ranged CSV export.
8. [x] Parent controls: app-name selection and named child profiles.
9. [ ] Rules: custom deny patterns, expiring allows and scheduled profiles.
10. [ ] Privacy: explicit cloud mode, retention/deletion and PIN-gated local clear.
11. [ ] Diagnostics: attribution/failure/drop metrics and configurable logged HTTPS-record handling.
12. [ ] Packaging: feed health/removal policy, release metadata/icon/settings and consistent docs.

## Verification discipline

Run JVM regressions and both APK builds after each functional chunk; run full
lint and Firebase emulator checks for relevant changes and at the end. Parse
JUnit/lint XML for actual totals. Passing an emulator rules test is not evidence
that the Android application workflows ran.

## Permission-dependent work

Do not read or modify credential files. Do not deploy Firebase rules, configure
real accounts/providers, publish a store release, enroll device-owner mode, or
change system VPN settings unattended. Implement and test local code when
possible, and leave deployment/account/device actions explicitly pending.
No Android device was connected at the baseline; device verification is pending.

## Baseline results

- Both debug APKs assembled.
- 98 JVM tests passed; 70 Firebase emulator REST checks passed.
- Child lint: 1 error, 26 warnings. Parent lint: 28 errors, 10 warnings.
- Production Firebase and Android runtime checks were not performed.

## Completed changes

A checkbox means the scoped implementation was verified locally; remaining
production/device requirements are stated separately.

### 1 — Foreground VPN lifetime

- The Activity starts an FGS, the service immediately promotes itself, and API
  34 uses the declared `systemExempted` type and normal permissions.
- A separate low-importance ongoing channel opens the PIN-protected app controls;
  there is no notification action that bypasses the protection-off PIN.
- TUN failure stops the service; destruction removes its foreground notification.
- Verification: 98 JVM regression tests unchanged/passing, both debug APKs built,
  and `git diff --check` passed. Device survival/reboot/notification checks pending.
- Compatibility correction: this project's AndroidX core predates
  `ServiceCompat.startForeground`, so the implementation uses guarded platform APIs.

### 2 — Remote deletion and snapshot validation

- Missing rule nodes now replace remote state with empty allow/UID sets and
  restore local categories; local rule keys remain untouched.
- A pure-Java immutable adapter validates Firebase list/object shapes, domains,
  UID bounds, category types and entry limits. Invalid snapshots retain the
  previous valid state rather than crashing the application listener.
- Verification: 103 JVM tests passed, both APKs built, 73 Firebase emulator REST
  checks passed, including removal of the final list entries. Actual Android
  listener/preferences behavior still needs a connected-device check.

### 3 — Pairing retry, reconnection and durable revocation

- Parent connection waits for authentication and checks its own membership in
  both databases. Existing parents reconnect without rewriting membership;
  partial registration retries only the missing store with the original code.
- A partial registration with a different/expired code requires child-side
  PIN-gated revocation before a fresh pairing. The UI explains this rather than
  weakening membership rules or claiming a cross-database transaction exists.
- Revocation is persisted locally until both server deletions and membership
  read-backs confirm absence. Pending entries retry after process/network
  reconnection; the UI distinguishes pending from confirmed revocation.
- Verification: 108 JVM tests, both debug APK builds, and 76 emulator REST
  checks passed. Android callback/restart behavior and real-project access still
  need devices. A trusted server coordinator remains an optional deployment task.

### 4 — Validated transactional remote edits

- Allow/UID actions now transact one intent against the latest server list;
  category actions transact only their category family. They no longer replace
  unrelated rules from a stale screen snapshot. Empty lists still delete cleanly.
- Domains are validated before writing. Category defaults and sparse historical
  category maps render consistently with the actual child policy.
- The dashboard shows waiting, pending, server-accepted and failed states; a
  server acknowledgement is explicitly not presented as child-applied evidence.
- Verification: 114 JVM tests, both APK builds, and 83 emulator REST checks
  passed. Tests include rebased intents and a real stale-ETag rejection/retry;
  Android SDK transaction callbacks still need device integration testing.

### 5a — Bounded work and socket protection

- DNS work is capped at four workers and 128 pending packets; overload is
  rejected without running on the TUN reader. A separate bounded event worker
  prevents slow DNS forwarding from starving package broadcasts.
- Rejected packets are counted with rate-limited logging. Accepted asynchronous
  broadcasts finish on shutdown. A failed `protect()` now fails the upstream
  operation instead of silently sending an unprotected socket.
- Verification: 116 JVM tests and both APK builds passed, including bounded
  queue rejection/shutdown tests. Parser/locale fixes remain the other half of
  update 5 and are being reviewed independently.

### 5b — Codec boundaries and locale-independent rules

- DNS/IP parsers reject overflowing/truncated slices without reading backing
  padding or throwing. Compression decoding uses message-relative bounded hops.
- Pattern matching and local allow normalization use `Locale.ROOT`. Synthetic
  A replies now honor their address argument; escaped names round-trip without
  changing the question. TCP frames validate IDs/flags before normalization.
- Verification: 137 JVM tests passed and both APKs built. The original scratch
  reproductions now reject malformed inputs safely and match under both ROOT
  and Turkish locales. New coverage includes deterministic slice/name fixtures,
  strict UDP/IP lengths, short TCP reads and bounded worker rejection.

### 6 — Lint and credential-free CI

- Completed the parent Bengali resources, preserving the newer pairing/write
  messages during integration (disjoint-intent merge; duplicate key removed).
- Kept full app enumeration with a narrowly documented package-visibility lint
  exception. Also documented AGP 8.5's false alarm-only prerequisite for the
  configured-VPN `systemExempted` path; no exact-alarm permission was added.
- Added pinned GitHub Actions jobs for Java 17 tests/build/lint and Java 21 /
  Node emulator checks, a locked Firebase CLI, and always-uploaded reports.
  The generator uses inert demo-only build resources and refuses to read or
  overwrite existing Firebase config files. All five action SHA/tag pairs were
  verified against their upstream GitHub repositories.
- Local verification: 137 JVM tests, 3 Python generator tests, both APK builds,
  both lint tasks and 83 emulator checks passed. Lint has zero errors, with 25
  child and 15 parent warnings retained for subsequent targeted cleanup.
- GitHub-hosted run `40834087017` at `08a08f3` completed successfully: both the
  Android build/test/lint job and the demo Firebase emulator job passed.

### 7 — Retained history, selection statistics and ranged export

- Added a bilingual, private history Activity with an explicit Back button,
  scrollable controls, app/name/package/UID and literal-domain search, verdict
  filters, local-time date presets and inclusive custom calendar dates.
- Reads Room rather than the live ring. Stable timestamp/ID keyset pages hold
  at most 100 rows; a frozen insert-ID boundary excludes new/backdated inserts.
  Statistics cover the full selection. Filters, page boundary and scroll restore
  across recreation; changing filters or refreshing captures a fresh selection.
- PIN-gated ranged CSV streams all matching retained rows in bounded batches,
  detects pruning/clearing during export, cancels incomplete files, and shares
  only while the Activity is visible. Text cells in both export paths are
  spreadsheet-safe; the legacy quick export now states its 200-row maximum.
- Recovered the interrupted agent's partial files, then completed navigation,
  Activity/resources and regression coverage. Feature-8 groundwork was safely
  stashed during this independent checkpoint and restored afterward.
- Verification: 149 JVM tests and 9 Python tests passed, including six tests
  executing the production Java query builder against SQLite. Both debug APKs
  and both lint tasks passed with zero errors (29 child / 15 parent warnings).
  Device UI, Room callback/lifecycle and actual sharing checks remain pending;
  hosted run `40907969216` at `67951d1` subsequently passed both jobs.

### 8 — Consented app-name picker and named saved devices

- App-name snapshots are off by default and independently PIN-gated on the
  child. Pairing can proceed without this consent. Icons/app contents are never
  included; new-install cloud alerts respect the same app-name preference.
- Installed user-app metadata is validated and capped at 1000 packages. A
  bounded single-flight publisher coalesces refreshes, removes uninstalled
  packages and serializes opt-out deletion after an in-flight upload finishes.
  Startup, connectivity, pairing membership and package changes refresh it.
- Added a searchable bilingual parent app picker, package/UID/update details,
  shared-UID warnings and intent-based block/unblock transactions. Cached app
  snapshots are labelled and cannot enable editing. Success still means server
  acceptance, not proof of a child-applied rule.
- Up to 20 parent-local named profiles support rename, verified selection,
  additional pairing and local forgetting. Selecting a saved UID checks both
  memberships (Firestore explicitly from the server); Firebase remains the
  authorization boundary. An empty code reconnects only existing membership.
  Forgetting explicitly does not revoke cloud access or recall downloaded data.
- Added child-owned strict-schema Firestore app rules. No production rules
  were deployed. Opt-out requests snapshot removal when online, not deletion of
  prior history/events or copies already held by another device.
- Verification: 160 JVM tests, 9 Python tests and 110 emulator REST checks
  passed; both APKs and both lint tasks passed with zero errors (30 child / 20
  parent warnings). Emulator coverage includes a real 400-write metadata batch,
  paginated collection read-back, invalid records, parent/stranger write denial
  and revoked reads. Single-flight state regressions cover queued opt-out and
  stale completion. Android callback timing, package broadcasts, UI/recreation,
  sharing opt-out and real-project integration still require connected devices.
