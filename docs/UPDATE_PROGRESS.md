# Post-v2.1.0 update ledger

Baseline: annotated tag `v2.1.0` (formerly `v1.1.0`), commit `93defce`.
The work branch retains its original name, `updates/post-v1.1.0`; release tags
were normalized for v2.2.0 without changing commit history.
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
9. [x] Rules: custom deny patterns, expiring allows and scheduled profiles.
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

### 9 — Local deny rules, expiring allows and weekly profiles

- Integrated the recovered rules work, registered its private Activity and added
  main-screen navigation. English/Bengali controls have explicit Back, scrolling,
  draft restoration and per-action PIN gates, including returned file selections.
- Local permanent/unexpired allows precede local custom denies, app blocks and
  category blocks. Managed parent decisions are authoritative first; local
  exceptions/imports cannot weaken a parent block. Weekly profiles add blocks,
  combine deterministically and use device-local start-inclusive/end-exclusive
  intervals with overnight/week wrap. Expiries are absolute, never renewed by
  import/restart. No new alarms, permissions or VPN restart are required.
- Bounded v2 local backups import valid v1 documents; strict grammar/type/key,
  count/depth/UTF-8/byte validation precedes one replacement preference edit.
  PIN, pairing, history and managed rules are excluded. Fresh export URIs keep
  earlier shared backups intact. Files are not shared from destroyed/hidden UI.
- Review additions bound editor/backup queues, coalesce refreshes, suppress
  stale read results and disable conflicting controls during imports/mutations.
  A bad v2 detail document retains a loaded policy; a cold start still loads
  legacy/base rules instead of leaving an empty engine. Invalid details still
  require authorized repair/import; they are not claimed to be recovered.
- Verification: 184 JVM tests and 9 Python tests passed, both APKs and both
  lint tasks passed with zero errors (30 child / 20 parent warnings). APK
  manifest inspection confirms the editor exists and is non-exported; all 71
  feature-resource keys match across languages. Actual Android PIN/picker,
  preference recovery, schedule/time-zone behavior and UI flows remain pending.

### Device follow-up — DNS socket recovery (2026-11-04)

- Both apps were tested on the same authorized Samsung SM-A127F / Android 13.
  Parent anonymous authentication succeeded. The normal Child PIN gate was
  authorized, and Child generated a real pairing code after both database
  writes completed, without enabling optional app-name sharing.
- Parent pairing then failed at its server-only Firestore membership read:
  `UNAVAILABLE` / `UnknownHostException` for `firestore.googleapis.com`.
  The VPN logged repeated upstream `Pending connect failure` errors. This was
  a DNS-forwarding failure, not evidence of invalid pairing codes or denied
  Firebase permissions.
- Fixed the forwarder to keep healthy sockets protected and connected to one
  resolver, use the checked connect overload for new sockets, and close failed
  sockets instead of returning them to the pool. A different resolver gets a
  fresh socket. Four deterministic regressions cover failure eviction,
  resolver fallback, healthy reuse and peer changes; the two failure-eviction
  regressions were observed failing before the fix.
- Verification: 188 JVM tests passed; both debug APKs and both lint tasks passed
  with zero errors (30 child / 20 parent warnings). Both updated APKs were
  installed using data-preserving replacement and their device hashes matched
  the local artifacts. No production Firebase rules or console settings changed.
- Physical retest: all 21 ordinary test-app DNS response checks passed across
  three batches, including the current metadata-suppression behavior. All four
  independent HTTPS reachability checks succeeded. The new VPN process logged
  zero forwarding warnings / pending-connect failures during this retest and
  no crash-buffer entries. These checks do not establish history completeness,
  successful pairing, SDK callbacks or a parent-to-child rule round trip.
- Post-fix pairing is still pending: the phone reached its Android lock screen,
  and the request for a normal user unlock timed out. No lock-screen interaction
  or PIN retry was attempted. USB stay-awake was restored and read back; the
  temporary test helper was uninstalled and its absence verified. Child
  protection remains foreground/running. Earlier missing HTTPS/SVCB audit rows
  and the non-PIN local history-clear flow remain separate unresolved defects.

### Shared authentication — email, Google and phone (2026-11-04)

- Added a shared Java Android `:auth` library and account entry/status in both
  apps: email/password (including Gmail addresses), creation/linking, password
  reset, email verification/refresh, same-account email reauthentication, Google
  Credential Manager and phone OTP. Guest and local-only entry are explicit.
- Existing identities link providers, never silently sign in as another UID.
  Durable installation anchors guard startup, cloud facades and callbacks.
  Background startup/events no longer create anonymous users. No normal
  primary sign-out/switch/merge/delete is exposed for either role; Parent and
  Child require different identities under the existing device-keyed schema.
- Recovery is verified in an isolated named Firebase app with no database
  clients. Only the exact bound UID reaches primary auth via `updateCurrentUser`.
  This prevents wrong-account recovery from exposing SDK-owned pending writes
  to another principal and avoids consuming a phone OTP twice. Secondary SDK
  sessions are cleared; stale operations never promote a candidate.
- Child account changes retain the PIN gate, including recovery on previously
  protected installs. Account activity is private and secure-windowed; passwords
  and OTPs are not saved/restored. Phone consent starts unchecked, transient
  tokens remain in RAM, and generation/completion/cooldown guards reject stale
  or duplicate work. English/Bengali parity covers 81 strings and one plural.
- Verification: 245 regular JVM tests (188 core / 57 auth), 8 real Android SDK
  tests against a local demo Auth emulator and 15 Python tests passed. Both APKs
  built; all three lint tasks passed with zero errors (8 auth dependency-version
  advisories, 30 Child / 20 Parent warnings). SDK tests verify guest linking,
  collision retention, exact/wrong recovery, session cleanup, stale ownership,
  existing-primary retention and phone credential copying/token refresh.
- Robolectric includes simulated API 24/API 34 UI and preference behavior,
  rotation/secret clearing, validation, guest/recovery restrictions and heading
  compatibility. Compiled manifests confirm non-exported account activities
  and no SMS/contacts/phone-number reading permissions. CI now runs auth unit,
  real-SDK demo-emulator and lint tasks and retains their reports.
- Setup guide: `docs/authentication.md`. At this checkpoint both generated app
  symbol tables lacked `default_web_client_id`, so Google console/OAuth setup was
  still required. This is superseded by the Google sign-in removal below. No
  production providers, billing, rules or accounts were changed. No real Google
  chooser, email action, SMS delivery, reCAPTCHA or Play Integrity success is claimed.
  ADB had no attached device; new auth APKs were built, not installed.
  Earlier physical pairing/round-trip and privacy/diagnostics/feed tasks remain
  separate pending work; no app data, protection PIN or managed policy was reset.

### Google sign-in removal (2026-11-06)

- Removed the nonfunctional Google sign-in/link button and its Credential Manager,
  Google ID token, OAuth client lookup and lifecycle code. Email/password, phone OTP,
  guest and local entry remain available.
- Removed the direct Credential Manager and Google ID dependency declarations. Firebase
  Auth still supplies its own transitive support libraries. Existing sessions are not
  signed out or replaced; the immutable identity safeguards remain.
- Updated English/Bengali resources, tests and authentication documentation. A source
  contract now prevents the Google control, implementation symbols or direct dependency
  declarations from being reintroduced accidentally.
- Verification: 188 core and 66 auth JVM tests passed, as did all 21 Python contracts.
  Both debug APKs built, and Auth, Child and Parent lint completed with zero errors.
  No Firebase Console setting, production account, app data or signing configuration
  was changed.

### On-device audit fixes — startup, refresh and pairing errors (2026-11-07)

- Reproduced three child ANRs (30 s cold starts) and traced them to the Firebase
  rules callback calling `BlocklistStore.applyRemoteRules` synchronously on the
  main thread. The same load also delayed the tunnel: `establish()` ran only
  after all four categories were parsed, so the UI reported "Protection is off"
  for roughly 25 s while the service was already starting.
- The hosts parser now makes one pass per line and one parse per host, with
  manual token/label scanning instead of a regex per host. Parsed categories are
  cached in memory and reloads reuse any list whose downloaded file has not
  changed. A cold 101,514-rule load dropped from 30 s to 9.2 s on the phone and
  now runs on a `tracewall-rules` thread.
- `TracewallApp` preloads the policy on a background thread and applies parent
  snapshots through a coalesced background reload. The main thread now only
  writes preferences; it never waits for the shared policy lock.
- `DnsVpnService` establishes the tunnel first and loads the policy afterwards.
  Queries queue unanswered until the engine is ready, so no request bypasses the
  filter. A live `starting` state and bilingual "Starting protection…" copy
  replace the false "off" state until the tunnel is actually running.
- Replaced the retired abuse.ch hostfile endpoint (HTTP 410) with the maintained
  `urlhaus-filter` hosts mirror. A refresh that replaces at least one list now
  records a success, so one dead source cannot pin the UI to "Never"; the
  updated/failed counts still show a partial refresh.
- Tightened `PairingCode.validChildUid` to the 20–128 character Firebase UID
  shape and added invalid-UID, not-found, permission and network outcomes to
  `FirebaseParent.verifyAccess`. The pairing screen now says the UID is
  incomplete or that no pairing matched instead of blaming connectivity.
- Verification: 206 JVM/Robolectric tests pass (189 core, 11 child, 6 parent);
  both debug APKs built. Data-preserving installs matched the local SHA-256
  values. On the SM-A127F: cold start 4.1 s with zero new ANRs, cold policy load
  9.2 s on `tracewall-rules`, warm tunnel 71 ms, cold tunnel 6.7 s with the
  starting state visible, `doubleclick.net` → 127.0.0.1 while `example.com`
  resolved normally, full 4/4 list refresh with 1,598 rules added, invalid UID
  rejected locally, and a well-formed unknown UID reported as not found.
- Phone OTP was retried after the project's SMS region policy was updated to
  allow Bangladesh (`BD`). The request now fails later with error 17499
  `BILLING_NOT_ENABLED`: the project has no billing account
  (`billingEnabled: false`). No SMS was sent. Enabling billing is a project-owner
  decision, so phone login remains unverified; it is no longer a region-policy
  problem.
- Still pending: phone OTP delivery (billing disabled), parent dashboard/remote
  rules/history/revocation (no child paired; PIN unknown), PIN-gated child
  actions, per-app enforcement attribution and boot/always-on behavior. No parent
  PIN was guessed or reset.
