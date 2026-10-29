# Post-v1.1.0 update ledger

Baseline: annotated tag `v1.1.0`, commit `93defce`. Work branch:
`updates/post-v1.1.0`. Existing historical tags and `main` are preserved.
Each completed implementation is tested and committed separately, then pushed.

## Acceptance checklist

1. [x] VPN foreground lifecycle: API-compatible foreground notification and cleanup.
2. [x] Empty remote snapshots clear persisted remote rules, preserving local rules.
3. [x] Pairing: recover partial membership, confirm revocation, reconnect existing parents.
4. [x] Remote edits: validate domains, show write results, avoid concurrent lost edits.
5. [ ] Core: bounded DNS workers, safe parser slices, locale-independent matching, regressions.
6. [ ] Verification: deliberate lint fixes and reproducible GitHub CI.
7. [ ] History: persistent filtered browsing, statistics, pagination/ranged CSV export.
8. [ ] Parent controls: app-name selection and named child profiles.
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
