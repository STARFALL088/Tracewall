# Post-v1.1.0 update ledger

Baseline: annotated tag `v1.1.0`, commit `93defce`. Work branch:
`updates/post-v1.1.0`. Existing historical tags and `main` are preserved.
Each completed implementation is tested and committed separately, then pushed.

## Acceptance checklist

1. [ ] VPN foreground lifecycle: API-compatible foreground notification and cleanup.
2. [ ] Empty remote snapshots clear persisted remote rules, preserving local rules.
3. [ ] Pairing: recover partial membership, confirm revocation, reconnect existing parents.
4. [ ] Remote edits: validate domains, show write results, avoid concurrent lost edits.
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

Implementation entries, commit references and verification are recorded here as
work lands. A checkbox means the scoped implementation was verified locally;
remaining production/device requirements are stated separately.
