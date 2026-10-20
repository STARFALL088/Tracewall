# Tracewall

On-device DNS filtering for Android. No account, no server, no root: the app
runs a local `VpnService` that carries only DNS, checks every lookup against a
rule set on the phone, and shows which app asked for which name.

Built for the Advanced Programming Laboratory Android project (30 marks), which
is why the feature set maps week-by-week onto that syllabus. It is also a
genuinely useful app rather than a rubric exercise.

## Modules

    blocklist/   pure JVM. No android.* imports at all.
                 - DnsMessage      RFC 1035 wire codec (parse, build, compression)
                 - IpPacket        IPv4/IPv6 + UDP codec, real checksums
                 - DnsForwarder    upstream UDP, TCP retry on truncation, socket pool
                 - DomainPattern   exact + label-aligned wildcard matching
                 - HostListParser  hosts-format lists with honest parse counts
    app/         Android. VpnService, blocklist store, live query log, UI.

The split is the point: the hardest code (wire formats, checksums) is testable
on a laptop in milliseconds with JUnit and no emulator. `./gradlew
:blocklist:test` runs 65 tests, including checksum re-verification against an
independent RFC 1071 reference implementation.

## How the tunnel works

    device address   10.66.0.2/24
    advertised DNS   10.66.0.1
    routed           10.66.0.1/32 only

Only packets to that one address enter the tunnel, so nothing but DNS can be
affected by a bug here. Each query is parsed, checked against the rule set, and
either answered locally (`0.0.0.0`, TTL 0) or forwarded to the network's real
resolver over a socket that is passed through `VpnService.protect()` - without
that call the forwarder's own queries loop back into the tunnel forever.

Replies are rebuilt with correct IPv4 header and UDP checksums. Android does not
fix those up for a TUN write, and a wrong checksum means the client silently
discards the answer.

## Build

    ./gradlew :blocklist:test        # engine, no Android SDK needed
    ./gradlew :app:assembleDebug     # APK -> app/build/outputs/apk/debug/

Requires JDK 17 (pinned in `gradle.properties`; AGP 8.5 does not tolerate the
newer default JDK) and the Android SDK with platform 34 + build-tools 34.
`local.properties` points at `~/Android/Sdk` on this machine.

## Status

Working: DNS interception, blocking, forwarding, live log, English + Bengali.

Not yet: per-app attribution (`getConnectionOwnerUid`), RecyclerView history,
Room persistence, custom trend view, ContentProvider export, boot receiver,
remote rules from the parent app.

Known limits, deliberately: UDP DNS only (no DNS-over-TLS), IPv4 only, and a
user who turns on Private DNS bypasses the filter entirely - the app should say
so rather than pretend.

## Rule data

`app/src/main/assets/categories/` holds one hosts-format file per category.
`malware.txt` is a snapshot of the abuse.ch URLhaus hostfile; regenerate it with
`scripts/fetch-malware-list.sh`. The other three are short curated seeds, sized
to prove the category works rather than to be a complete blocklist.
