# Tracewall

On-device DNS filtering for Android. No root: the app runs a local
`VpnService` that carries only DNS, checks every lookup against a rule set on
the phone, and shows which app asked for which name.

Since v2 there is an optional second app for the parent and a small Firebase
backend (anonymous Authentication, Realtime Database, Cloud Firestore) that
lets a parent push rules to the child's phone and read its history. The
filtering itself still happens entirely on the child's device; Firebase only
carries rules and history.

Built for the Advanced Programming Laboratory Android project (30 marks), which
is why the feature set maps week-by-week onto that syllabus. It is also a
genuinely useful app rather than a rubric exercise.

## Versions

| Version | Commit    | Date       | What it is                                                                                                                           |
| ------- | --------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| **v1**  | `5016506` | 2026-10-20 | Standalone child-side DNS filter: VpnService, policy engine, per-app blocking, Room history, English + Bengali. No network services. |
| **v2**  | tag `v2`  | 2026-10-26 | v1 plus Firebase, the `:parent` app, remote rules and categories, QR pairing, Private-DNS warning, SVCB/HTTPS blocking.              |

    git checkout v1     # the standalone filter, as first pushed
    git checkout v2     # everything in this README

### What v2 added over v1

- **Firebase** (Auth, Realtime Database, Firestore) in `:app`, with offline
  persistence for both databases. `TracewallApp` signs in anonymously at
  startup and attaches the rules listener.
- **`:parent` module**, a second Android app (Java): pairing screen, dashboard
  with allow-domain, block-UID and category switches, live history list.
- **Remote rules.** Parent-pushed allow list, blocked UIDs and category set are
  stored under separate keys and merged with on-device rules, so the parent
  never erases what the child set locally.
- **Firestore history mirror.** `HistoryWriter` copies each flushed batch to
  `/devices/{uid}/history` after the Room insert.
- **QR pairing.** Tap the device code on the child to show a QR of its UID; the
  parent scans it (ZXing) instead of typing 28 characters. Long-press copies it.
- **Private DNS warning.** Banner on the main screen when
  `Settings.Global private_dns_mode` is anything but `off`, rechecked on every
  resume.
- **SVCB / HTTPS blocking.** Type 64/65 queries get an immediate NXDOMAIN, which
  closes the encrypted-DNS discovery path (`_dns.resolver.arpa`) that v1
  listed as a known gap.
- **Thread safety.** `DnsVpnService.running` is an `AtomicBoolean`.
- **BootReceiver** pre-warms the Firebase sign-in after a reboot.
- **Security rules** in `firebase/` (see below), plus
  `app/google-services.json.example`.

## Verified on a device (emulator, API 37) - v1 behaviour

```
$ adb shell ping -c 1 example.com      # allowed
PING example.com (104.20.23.154)

$ adb shell ping -c 1 xvideos.com      # category: adult
PING xvideos.com (127.0.0.1)           # sinkholed 0.0.0.0 -> 127.0.0.1

$ adb shell ping -c 1 pornhub.com      # after long-press -> Allow
PING pornhub.com (66.254.114.41)       # allowlist beats the category, live
```

Screenshots in `docs/screenshots/`: blocking with per-app attribution, and the
allowlist dialog. The header read `Rules: 516` after the allow, which is the
policy engine reloading in place without restarting the tunnel.

Nothing added in v2 has been run on a device yet. See [Status](#status).

## Modules

    blocklist/   pure JVM. No android.* imports at all.
                 - DnsMessage      RFC 1035 wire codec (parse, build, compression)
                 - IpPacket        IPv4/IPv6 + UDP codec, real checksums
                 - DnsForwarder    upstream UDP, TCP retry on truncation, socket pool
                 - DomainPattern   exact + label-aligned wildcard matching
                 - HostListParser  hosts-format lists with honest parse counts
                 - PolicyEngine    precedence: allow > app block > category
    app/         Child's phone. VpnService, rule store, Room history, provider, UI.
                 - TracewallApp    Application: anonymous sign-in, rules listener
                 - sync/FirebaseSync  facade over Auth, Realtime DB and Firestore
    parent/      Parent's phone. Pairing, remote rules, history viewer.
                 - sync/FirebaseParent  pairing, pushRules, pushCategories, listenHistory
    firebase/    Security rules to deploy: database.rules.json, firestore.rules

The split is the point: the hardest code (wire formats, checksums) is testable
on a laptop in milliseconds with JUnit and no emulator. `./gradlew
:blocklist:test` runs 77 tests, including checksum re-verification against an
independent RFC 1071 reference implementation and 11 policy-precedence tests.

## How the tunnel works

    device address   10.66.0.2/24
    advertised DNS   10.66.0.1
    routed           10.66.0.1/32 only

Only packets to that one address enter the tunnel, so nothing but DNS can be
affected by a bug here. Each query is parsed, decided, and either answered
locally (`0.0.0.0`, TTL 0) or forwarded to the network's real resolver over a
socket that is passed through `VpnService.protect()` - without that call the
forwarder's own queries loop back into the tunnel forever.

Replies are rebuilt with correct IPv4 header and UDP checksums: Android does not
fix those up for a TUN write, and a wrong checksum means the client silently
discards the answer.

The service follows the default network, so a Wi-Fi to mobile-data switch
rebuilds the forwarder instead of leaving every lookup to time out.

SVCB and HTTPS queries (types 64 and 65) never reach the policy engine or the
upstream: they are answered NXDOMAIN on the spot, so Android cannot discover a
DoH/DoT server to route around the filter.

## Rule precedence

1. Explicit allow (exact or wildcard) - wins over everything. Long-press a
   blocked row to add one, or push one from the parent app.
2. Per-app block (whole uid).
3. Category blocklist (malware, gambling, adult, ads).
4. Default allow.

Categories live in a sorted map, so a host that appears in two of them always
reports the same reason.

Local and remote rules are merged before the engine is built: the allow list and
the blocked-UID set are the union of what the child set and what the parent
pushed. The category set is different: if the parent has pushed one it replaces
the on-device default; if not, the default applies.

## Parent sync (Firebase)

Realtime Database carries rules (low latency, parent to child). Firestore
carries history (queryable, child to parent).

    /devices/{childUid}/rules/allow          {0: "example.com", ...}
    /devices/{childUid}/rules/blocked_uids   {0: 10123, ...}
    /devices/{childUid}/rules/categories     {malware: true, ads: false, ...}
    /devices/{childUid}/parentUid/{parentUid} true            (RTDB, pairing)

    Firestore  /devices/{childUid}/history/{auto-id}          one doc per query
    Firestore  /devices/{childUid}/parents/{parentUid}        pairing mirror

**Pairing.** The child's anonymous Firebase UID is its device code. The parent
scans or types it, and `registerAsParent` writes the parent's own UID under both
databases. The security rules then grant that parent read/write on the child's
node.

**Setup.**

1. Create a Firebase project; enable Anonymous sign-in, Realtime Database and
   Firestore.
2. Download `google-services.json` for `tracewall.child` into `app/` and for
   `tracewall.parent` into `parent/`. Both are gitignored; the committed
   copies are placeholders and `app/google-services.json.example` shows the
   shape. Until they are replaced, every Firebase call fails at runtime.
3. Deploy the rules: `firebase deploy --only database,firestore:rules`. They are
   in version control but not active until deployed.

**Security model, honestly.** The child's UID is the shared secret. Any
authenticated user who knows it can register as a parent, because the
`parentUid` subtree is writable by any signed-in user. The UID is a 28-character
random string shown only on the child's screen, which is reasonable for a course
project but is not a substitute for a real pairing handshake (for example a
one-time code the child approves). Treat the device code like a password.

## History

`HistoryWriter` batches inserts into Room (1.5 s flush, bounded queue, one-week
retention) so the packet path never waits on disk. `QueryLogProvider` exposes the
table read-only with `exported=false`, and backs both the CSV export and the
clear action. After each Room insert the same batch is mirrored to Firestore so
the parent app can read it remotely; the Firestore SDK queues those writes while
offline and flushes them on reconnect.

## Build

    ./gradlew :blocklist:test             # engine, no Android SDK needed
    ./gradlew :app:assembleDebug          # child APK -> app/build/outputs/apk/debug/
    ./gradlew :parent:assembleDebug       # parent APK -> parent/build/outputs/apk/debug/

Requires JDK 17 (pinned in `gradle.properties`) and Gradle 8.9 (pinned in the
wrapper). AGP 8.5 supports neither JDK 27 nor Gradle 9, and both failures are
opaque - set the pins before bisecting anything.

## Status

**Verified on device (v1):** DNS interception, blocking, forwarding, per-app
attribution, live log, custom trend view, allowlist override, Room history, CSV
export, clear, English + Bengali.

**Built and compiling, 77/77 unit tests green, not yet run on a device (v2):**
per-app blocking screen, Firebase sign-in, remote allow/block/category rules,
Firestore history mirror, QR pairing, Private DNS banner, SVCB/HTTPS blocking,
the whole `:parent` app. The two `google-services.json` files are placeholders,
so none of the Firebase paths has run against a real project.

**Known gaps in v2:**

- `pushRules` replaces the whole `allow` and `blocked_uids` lists. The parent
  dashboard pushes one entry at a time, so a new allow rule overwrites the
  previous one. The parent should hold the full list locally and push it whole.
- Pairing trusts anyone who knows the child UID (see above).
- The parent's blocked-app field takes a raw Android UID; there is no app picker,
  because the parent cannot see the child's installed apps.
- The Firestore history mirror writes one document per query and has no
  retention, unlike the one-week Room retention on the child.
- NXDOMAIN for HTTPS (type 65) also blocks the HTTPS-record optimisation some
  CDNs offer; clients fall back to A/AAAA and still work.

**Known limits, deliberately:** UDP DNS only (no DNS-over-TLS), IPv4 tunnel. A
user who enables Private DNS can still bypass the filter; v2 warns about it but
cannot turn it off. The VPN cannot restart itself after a reboot, so
`BootReceiver` posts a notification instead; always-on VPN is the supported fix.

## Rule data

`app/src/main/assets/categories/` holds one hosts-format file per category.
`malware.txt` is a snapshot of the abuse.ch URLhaus hostfile; regenerate it with
`scripts/fetch-malware-list.sh`. The other three are short curated seeds, sized
to prove the category works rather than to be a complete blocklist.
