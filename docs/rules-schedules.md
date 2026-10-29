# Feature 9: local rules and weekly profiles

## Integrated entry point

`tracewall.child.ui.RulesActivity` is reachable from the main-screen local-rules
button. The child manifest registers the editor as internal/non-exported:

```xml
<activity android:name=".ui.RulesActivity" android:exported="false" />
```

`R.string.rules9_title` is the navigation label. Do not add a shortcut that
mutates rules or exports backups without `PinGate.require`. The Activity has a
fixed visible Back button because the child theme has no ActionBar. All
controls are vertically stacked in a scrolling body with wrap-content heights;
large-font and Bengali rendering still require a device/emulator check.

## Policy semantics

The existing three-argument `PolicyEngine` constructor remains supported.
The richer snapshot is immutable, Android-free and evaluates an injected
instant/time zone in tests. Production supplies the current wall clock and
current device time zone on every intercepted DNS decision.

1. Managed/parent policy first: its allowlist, blocked UIDs, active categories.
   A non-default managed decision is authoritative. Local edits/imports cannot
   weaken a parent block; a parent allow can override a local deny/profile.
2. Local permanent or unexpired allow.
3. Local custom deny.
4. Local base or active-profile app UID block.
5. Base or active-profile category block.
6. Default allow.

Exact patterns match only the named host. `*.example.com` matches any depth of
subdomain, not the apex or lookalike suffixes. Matching is case-insensitive and
label-aligned. The editor accepts only ASCII/punycode domain patterns, not
URLs. Duplicate patterns normalize to one entry. Profile names must be unique
case-insensitively and are ordered deterministically for reported reasons.

Profiles are **additive**: overlapping enabled profiles combine their blocks;
they never disable a base category, remove another profile, or turn off the VPN.
UID rules affect only attributed intercepted DNS, not all network traffic.
UIDs may be shared or reassigned by Android; restoring a UID backup on another
device is not a package-identity migration. Unknown UID `-1` is never selectable.

## Time behavior

- Timed allows last 1–10,080 minutes when created. Their saved value is an
  absolute epoch-millisecond expiry, active strictly before that instant.
- Reload, process restart and backup import never renew an expiry. Expired
  entries remain listed as expired until explicitly removed. The clock is not
  tamper-proof: changing device wall time changes evaluation.
- Replacing the *same* permanent allow with a timed allow removes that permanent
  entry. Other broader/overlapping permanent allows still take precedence.
- Weekly day bits are Monday through Sunday (bits 0–6). Days identify when an
  interval **starts**. Sunday 22:00–06:00 remains active early Monday.
- Start is inclusive; end is exclusive. Equal times and empty day masks are
  rejected, rather than ambiguously treating them as zero/full-day intervals.
- The current device-local wall clock/zone is used, not a fixed zone saved in the
  backup. A DST-skipped interval does not execute; repeated wall-clock minutes
  execute on both occurrences. Zone/clock changes affect the next DNS query.
- No alarm, background service job, exact-alarm permission or VPN restart is
  required for a schedule transition. Already-cached DNS answers, connections,
  Private DNS/DoH and traffic outside the existing DNS interception path are not
  revoked or newly intercepted by this feature.
- Activity status labels are explicitly "at refresh"; they are not a background
  scheduler heartbeat.

## Storage, backup and authorization

`BlocklistStore` keeps legacy local preference keys authoritative for allow,
blocked UIDs and local categories. New fields are stored in `local_details_v2`.
A replacement is fully constructed/validated/serialized before a single
preferences edit changes all local rule keys; remote rules, enabled state,
PIN, pairing and history keys are untouched. A policy snapshot is built and
atomically swapped, never incrementally patched on the packet path. Legacy
unsupported allow entries cannot disable base filtering on a cold start and
can still be removed through the legacy allow cleanup path.

Malformed v2 detail storage retains an already loaded policy. On a cold start,
legacy/base filtering still loads rather than leaving the empty initial engine
active; malformed custom details are not claimed to be recovered and must be
repaired through an authorized valid import. Editor and backup work queues are
bounded. Refresh results older than a mutation/import do not replace the UI,
and conflicting controls are disabled until import success or failure.

`allow(Context, String)` now returns a boolean so legacy query-row callers do
not crash on unsupported DNS query names or limits. Existing source callers
may ignore the return value; the new editor checks it and reports rejection.

Version 2 JSON includes all version 1 fields plus:

- `deny`: array of domain patterns;
- `temporary_allows`: `{ "pattern": "…", "expires_at_ms": … }` objects;
- `profiles`: name, enabled, day bit mask, start/end minutes, category names and
  blocked UID arrays.

Valid version 1 backups still import; new families default to empty. Import is
**replacement**, so importing v1 explicitly clears v2 denies/timers/profiles.
Older app versions are not expected to read v2 exports. The exact JSON schema,
integer/boolean types, domain/name ranges and known category names are checked.
Unknown fields, duplicate object keys (including escaped equivalents), duplicate
profile names/temporary patterns, excessively nested input and trailing data
are rejected. A strict bounded grammar runs before `org.json`, avoiding
Android/JVM permissiveness differences and recursive nesting bombs.

Limits: 256,000 UTF-8 bytes; 1,000 entries per domain/UID/timer list; 8 profiles;
1,000 UID references total across profiles. Reading a document also caps stream
bytes and validates UTF-8. Invalid imports never reach the preference write.
Serialization checks the total byte budget before a local mutation writes.

New Activity mutations, profile enable/disable saves, removal, exports and
imports are PIN-gated per action, not by a cached unlocked-screen flag. The
import result is gated again after the system picker. `BlocklistStore` and
`RulesTransfer` remain storage/I/O seams, not authorization boundaries; any
new caller must honor that same gate.

Exports use a fresh cache filename/URI rather than overwriting `rules.json` or
an earlier shared export. Save the shared file outside the app for a durable
backup: Android may clear export cache. No PIN, credentials, parent rules,
pairing state or history is exported.

## Local verification

Verified in this isolated worktree with Java 17 / SDK 34:

- `:blocklist:test`: **161 tests, 0 failures, 0 errors, 0 skipped** (24 added).
- `:app:assembleDebug` and `:parent:assembleDebug`: both successful.
- `:app:lintDebug`: **0 errors, 25 existing warnings**; no findings in the new
  Activity, layout or bilingual rule resources.
- `:app:testDebugUnitTest`: `NO-SOURCE`; this is not Android runtime coverage.
- English/Bengali feature resources: 71 matching keys and matching format
  placeholders; no Android imports in the pure-Java core; `git diff --check`
  passed.
- Build log: ignored `build/feature9-verification.log`; APKs and XML reports are
  in the standard module build directories. Both builds used generated inert
  `demo-tracewall` config fixtures, never real-project credentials.

The pure-Java tests cover precedence, wildcard/apex boundaries, exact expiry,
reconstructed snapshots, duration overflow, additive/disabled/overlapping
profiles, stable reasons, all weekday bits, Sunday overnight wrap, timezone
changes, DST skip/repetition, immutable snapshots, editor intents, v1/v2 round
trips, expired backup entries, type/range/count/byte limits, duplicate/unknown
fields, strict grammar and nesting bombs. No test waits for real time to pass.

Run with Java 17, SDK 34, the checked-in Gradle 8.9 wrapper/AGP 8.5.2 and existing
dependencies. The checked-in CI generator can create **inert demo-only** build
resources when no config exists; it refuses to read/overwrite existing config.
These are build fixtures, not real-project credentials or Android runtime
verification. No Firebase backend or permissions need changing for this feature.

Required device checks after integration: navigation/Back, PIN cancel/failure/
lockout/setup, rotation and draft restoration, Bengali plus large font/keyboard
scrolling, document picker cancellation/permissions, actual preference persistence
across process death/reboot, policy reload while the VPN is running, time/zone
changes and DNS-cache expectations. No connected-device or live Firebase checks
were performed in this isolated task.
