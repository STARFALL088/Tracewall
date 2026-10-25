# Tracewall

On-device DNS filtering for Android. No account, no server, no root: the app
runs a local `VpnService` that carries only DNS, checks every lookup against a
rule set on the phone, and shows which app asked for which name.

Built for the Advanced Programming Laboratory Android project (30 marks), which
is why the feature set maps week-by-week onto that syllabus. It is also a
genuinely useful app rather than a rubric exercise.

## Verified on a device (emulator, API 37)

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

## Modules

    blocklist/   pure JVM. No android.* imports at all.
                 - DnsMessage      RFC 1035 wire codec (parse, build, compression)
                 - IpPacket        IPv4/IPv6 + UDP codec, real checksums
                 - DnsForwarder    upstream UDP, TCP retry on truncation, socket pool
                 - DomainPattern   exact + label-aligned wildcard matching
                 - HostListParser  hosts-format lists with honest parse counts
                 - PolicyEngine    precedence: allow > app block > category
    app/         Android. VpnService, rule store, Room history, provider, UI.

The split is the point: the hardest code (wire formats, checksums) is testable
on a laptop in milliseconds with JUnit and no emulator. `./gradlew
:blocklist:test` runs 76 tests, including checksum re-verification against an
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

## Rule precedence

1. Explicit allow (exact or wildcard) - wins over everything. Long-press a
   blocked row to add one.
2. Per-app block (whole uid).
3. Category blocklist (malware, gambling, adult, ads).
4. Default allow.

Categories live in a sorted map, so a host that appears in two of them always
reports the same reason.

## History

`HistoryWriter` batches inserts into Room (1.5 s flush, bounded queue, one-week
retention) so the packet path never waits on disk. `QueryLogProvider` exposes the
table read-only with `exported=false`, and backs both the CSV export and the
clear action. The parent app will read through that authority rather than
opening a second database.

## Build

    ./gradlew :blocklist:test        # engine, no Android SDK needed
    ./gradlew :app:assembleDebug     # APK -> app/build/outputs/apk/debug/

Requires JDK 17 (pinned in `gradle.properties`) and Gradle 8.9 (pinned in the
wrapper). AGP 8.5 supports neither JDK 27 nor Gradle 9, and both failures are
opaque - set the pins before bisecting anything.

## Status

Working and verified on device: DNS interception, blocking, forwarding, per-app
attribution, live log, custom trend view, allowlist override, Room history, CSV
export, clear, English + Bengali.

New, built but not yet re-verified on device: per-app blocking. Long-press any
history row to block the app that made the query, or manage the list from the
`Apps` action on the main screen (switch per installed app, search included).
The policy path underneath is exactly the per-app rule the tests cover: all DNS
from a blocked uid sinks, allowlist entries still win, and unblocking resets
without a tunnel restart.

Firebase is wired up (Authentication, Realtime Database, Firestore) but the
remote-rules flow from the parent app is not yet connected end-to-end.
Replace `app/google-services.json` with the real file from your Firebase
console before running on a real device. A DoH/Private-DNS warning screen is
also not yet built.

Known limits, deliberately: UDP DNS only (no DNS-over-TLS), IPv4 tunnel, and a
user who enables Private DNS bypasses the filter entirely. The device also asks
for `_dns.resolver.arpa` (type SVCB) at boot - that is the encrypted-DNS
discovery path and it is not blocked yet.

## Rule data

`app/src/main/assets/categories/` holds one hosts-format file per category.
`malware.txt` is a snapshot of the abuse.ch URLhaus hostfile; regenerate it with
`scripts/fetch-malware-list.sh`. The other three are short curated seeds, sized
to prove the category works rather than to be a complete blocklist.
