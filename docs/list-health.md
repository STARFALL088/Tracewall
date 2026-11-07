# Local feed health and bundled-seed policy (update12 slice)

## Semantics and limits

The default is unchanged: **BUNDLED + FRESH**, not an accumulating union with
previous downloads. Download-only removals already worked. `RETAIN_BUNDLED`
keeps bundled seeds pinned; the explicit `FEED_ONLY` setting assembles only the
new validated feed, allowing upstream removals to remove bundled seeds too.
Changing the setting itself does not rewrite caches, reload rules, start/stop
the VPN, change category switches, or edit parent-managed rules. Each category
adopts the selected policy only on its next successful replacement. A mixed
set of applied cache policies during partial failure is intentional and visible.

The shared `ListData.validate(text, 100)` policy-loader contract is retained.
Validation requires at least 100 **unique** parsed hosts, zero malformed rows,
strict UTF-8 decoding, and no more than 5,000,000 bytes for either the raw input
or the final assembled text (including normalized address prefixes, attribution,
and cache metadata). Bare-host trailing junk, unknown section headers and
non-blocking hosts-file addresses are rejected rather than silently discarded.
Duplicate/comment rows cannot satisfy the minimum or mask shrinkage.

All HTTP, decoding, parse, raw-size, assembled-size, and shrink checks happen
before same-filesystem atomic cache replacement. Failure leaves the existing
category cache untouched. Without one, the existing loader uses the bundled
asset. The staging name is fixed per category, bounding process-death leftovers;
the next attempt removes/reuses it. No source URL or existing license declaration
was silently substituted. Saved metadata is **local observation**, not a live
upstream availability guarantee or an upstream signature.

### Deliberate shrink protection and recovery

After a successful new-format refresh, compare the next feed's unique host
count with the last **accepted feed count**, not the bundled/assembled count.
A loss **greater than 25%** is rejected. Exactly 25% is accepted. A rejected
candidate does not advance this baseline. Legacy caches lack a reliable raw
feed count, so their first replacement uses the 100-host minimum and records a
baseline; the app never fabricates a baseline from their larger merged count.

The health screen explains the drop and offers an existing-`PinGate`-protected
approval of that category's **exact raw SHA-256 revision**, followed by retry.
Different content requires fresh approval; an approval is consumed on successful
replacement or invalidated by a different rejected shrink. Minimum, UTF-8,
parse and both byte-cap checks still apply. A legitimate approved shrink moves
the baseline forward, so protection does not become a permanent stale union.
A failed HTTP request does not wipe the current cache or erase success metrics.

The atomically replaced cache's bounded versioned comment stores the category,
accepted raw feed count, digest and applied policy. This is the authoritative
shrink baseline; a crash between cache rename and preferences commit cannot use
stale UI statistics to bypass it. Malformed/unknown-version cache metadata fails
closed with `METADATA`; it is not silently treated as a new legacy baseline.
That exceptional local-corruption case needs storage/support repair, not a
blanket shrink override. The known text cache is not deleted by this screen.

### Bounded work and health records

- Four configured categories only. One latest health record per category,
  at most 4,096 UTF-8 bytes each; one optional 64-character approval per category.
  This is not a growing attempt/history log.
- Records retain last attempt/success/failure time, typed failure plus historical
  HTTP status, last successful feed/cache counts, raw/assembled bytes, parser
  lines/duplicates (malformed is necessarily zero on success), added/removed
  deltas, applied policy, and bounded response URL/ETag/Last-Modified metadata.
  Parser line counts include the final empty field after a trailing newline.
  Unknown/legacy metrics are labelled unknown, not zero-host online health.
- Header metadata is bounded and control-character checked. Invalid optional
  HTTP headers are omitted. Invalid health JSON/types/counts/source categories
  cannot authorize a replacement or an override.
- Manual refreshes, periodic refreshes, setting changes and approvals share one
  single-flight gate. Busy requests are coalesced/rejected, not queued behind
  a monitor. The executor queue is explicitly bounded. Existing
  `Result.updated/failed/added`, `success()`, and progress/done callbacks remain;
  `removed`, `busy`, and `retryable` are additive fields.
- At most three HTTPS redirects; no downgrade or user-info credentials;
  15-second connect / 20-second idle-read timeouts; 45-second source and
  180-second run budgets. A fixed-delay watchdog disconnects trickle downloads
  and stopped workers. Checks also surround validation/commit boundaries.
  Android/platform DNS and filesystem latency are not hard real-time promises.
- Existing unique daily WorkManager schedule and enabled/network constraints
  remain. A stopped/busy worker does not enqueue another run; only temporary
  network/time-limit/HTTP 429/5xx failures retry, at most twice per daily cycle.
  Permanent HTTP/validation/shrink failures wait for the next daily/manual run.

## Screen and integration status

`tracewall.child.ui.ListHealthActivity` is integrated and read-only on entry. It
has an explicit Back button, a vertically scrollable settings/cards area, wrapped
text, 48dp minimum action targets, compatibility accessibility headings, stable
card views (to preserve focus), polite live status, and 46 matching EN/BN
resource keys with matching format placeholders. Policy changes, shrink
approvals and manual refresh from this screen require the existing `PinGate`;
PIN setup alone does not authorize them.

Integration completed on `main` in commit `5bd28b3`:

1. `.ui.ListHealthActivity` is registered in the child manifest with
   `exported=false` and the `@string/feeds_title` label.
2. The Protect page launches it; no navigation entry bypasses the screen.
3. The old main-screen refresh action is PIN-gated and uses the compatible
   updater API with the additive `busy`/`removed` fields.
4. `BlocklistStore` retains its cached-text `validate(text, 100)` loading
   contract; the new cache header is a normal comment.

The read-only screen, TalkBack, EN/BN large-font/small-screen scrolling, PIN
lifecycle/rotation, Android cancellation and background scheduling, on-device
TLS/network/cache-rename failure injection, and confirmation that a running VPN
swaps rules without restarting are runtime checks still to perform. At the
7 November checkpoint the phone was locked before these checks could run; no
production credentials/configs were read and no Firebase calls or account
actions were performed.

## Read-only upstream observation

Observed **2026-10-29 at 17:05:29 UTC** (23:05:29 UTC+06), using bounded HTTPS
GETs against the four unchanged configured URLs, then parsing the downloaded
bodies with the implementation's pure Java validator. This is a point-in-time
check from this workstation, not an Android-device result or a test fixture.

| Category | Configured URL reference | HTTP | Raw bytes | Validated unique hosts | Result |
| --- | --- | ---: | ---: | ---: | --- |
| adult | `https://raw.githubusercontent.com/tiuxo/hosts/master/porn` [4] | 200 | 9,186 | 369 | Valid; zero malformed/duplicates |
| gambling | `https://raw.githubusercontent.com/bigdargon/hostsVN/master/extensions/gambling/hosts-VN` [5] | 200 | 82,682 | 4,033 | Valid; zero malformed/duplicates |
| ads | `https://raw.githubusercontent.com/tiuxo/hosts/master/ads` [6] | 200 | 42,057 | 1,729 | Valid; zero malformed/duplicates |
| malware | `https://urlhaus.abuse.ch/downloads/hostfile/` [7] | **410** | 389 | Not a feed | Gone HTML response; retain old cache/bundle |

The three successful bodies also passed assembly under **both** retention
policies. Resulting default retained-cache sizes were 1,499,588 / 407,453 /
640,597 bytes (adult/gambling/ads). Feed-only sizes were 9,424 / 82,504 / 42,290
bytes. These probe outputs were scratch artifacts, not committed feed snapshots.

The current primary URLhaus API documentation requires an Auth-Key for its
exports and restricts access to the hostfile documentation accordingly.[3]
The configured malware endpoint was subsequently changed to the maintained
`https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt`
mirror; a device refresh reported `Updated: 4 · Failed: 0 · Rules added: 1598`.
No credentials were added. The existing CC0 attribution is retained as an
existing declaration, not a newly verified assertion about the mirror's terms.
The observation above remains a point-in-time workstation result and is not
evidence that the old endpoint is still healthy.

## Verification checkpoint

Synthetic `demo-tracewall` Google Services files for **both** modules were
created exclusively by `scripts/ci/prepare-google-services.py --output-root .`.
There were no existing config files to inspect. These ignored, inert build
fixtures are not credentials, were not committed and were not used to run apps.
JDK 17, SDK 34, AGP 8.5.2, Gradle 8.9. The integrated re-run on 7 November 2026:

```text
: blocklist:test                                      PASS: 231 tests, 0 failures/errors/skips
: app:testDebugUnitTest                               PASS: 11 tests
: auth:testDebugUnitTest                              PASS: 52 tests
: parent:testDebugUnitTest                            PASS: 6 tests
: app:assembleDebug / parent:assembleDebug            PASS
: app:lintDebug / parent:lintDebug / auth:lintDebug    PASS: 0 errors
: EN/BN resources                                    PASS: 46 keys, equal placeholders
: git diff --check                                    PASS
BUILD SUCCESSFUL; 300 JVM tests total; 22 Python contracts passed
```

The feed tests cover prior-download removals, retained seed/overlap behavior,
feed-only seed removals, restoring retention, shrink threshold/dedup/baseline
and exact-content recovery, malformed/UTF-8/oversize input, a valid raw bare-host
list whose normalized output exceeds the cap, metadata schema/types/bounds,
response injection, historical failure retention, and bounded repeated records.

## Sources

[3] https://urlhaus.abuse.ch/api
[4] https://raw.githubusercontent.com/tiuxo/hosts/master/porn
[5] https://raw.githubusercontent.com/bigdargon/hostsVN/master/extensions/gambling/hosts-VN
[6] https://raw.githubusercontent.com/tiuxo/hosts/master/ads
[7] https://urlhaus.abuse.ch/downloads/hostfile
