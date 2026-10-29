# Syllabus coverage

DONE means implemented and built; device and production checks are listed separately.
The detailed week mapping below is preserved from the supplied root-level syllabus checklist.

| Task / syllabus area | Implementation | Status |
| --- | --- | --- |
| A — Firebase pairing and security | Scoped RTDB + Firestore permissions, expiring one-use QR/manual pairing, PIN-gated revocation | DONE — JVM, emulator rules, both APK builds |
| B — Rule data and parsing | Larger licensed hosts lists, parser robustness, load timing | DONE — JVM tests and both APK builds |
| C — Week 8: implicit intents | PIN-gated CSV sharing, VPN settings and resolution | DONE — JVM tests and both APK builds |
| D — Week 11: HTTP + JSON | HTTPS list updates, validated JSON import/export | DONE — JVM tests and both APK builds |
| E — Background data operations | Unique connected 24-hour WorkManager refresh | DONE — JVM regression tests and both APK builds |
| F — Dynamic broadcast receiver | New install notification and parent event feed | DONE — JVM, emulator rules, both APK builds; install device check pending |
| G — Lifecycle and state | Seven lifecycle callbacks, filter and scroll restoration | DONE — JVM + both APK builds; rotation device check blocked (AVD disk space) |
| H — Week 13: project report | Architecture, decisions, testing, limits and future work | DONE — report written; 98 JVM tests and both APK builds pass |

Existing components: VPN Service and sockets; Room with a private ContentProvider;
Activities and RecyclerViews; boot receiver; bilingual resources; local parent PIN.

## CSE 2200 syllabus mapping

Mapped to the Advanced Programming Laboratory 2026 lecture plan (Weeks 8 to 13) and the Week 13 demo checklist.
Status: DONE = implemented and built. Device validation is separate and is not implied by DONE. PARTIAL = incomplete; PLANNED = not yet implemented.

Paths are relative to the repo root; `J/` means `app/src/main/java/tracewall/child`.

## Week 8: Android Studio, Manifest, Lifecycle, Intents

| Topic | Where | Status |
|---|---|---|
| Project structure, Gradle (3 modules: `:blocklist`, `:app`, `:parent`) | `settings.gradle.kts`, `*/build.gradle.kts` | DONE |
| AndroidManifest, permissions, `exported` flags | `app/src/main/AndroidManifest.xml` | DONE |
| Activity lifecycle callbacks | Both child Activities log all seven callbacks; save filter/scroll state | DONE — built; rotation verification pending (no device; AVD disk space) |
| XML layouts, view binding | `app/src/main/res/layout/` | DONE |
| Click listeners / event handling | `J/MainActivity.java`, `J/ui/AppBlockActivity.java` | DONE |
| Explicit intents | `MainActivity` to `AppBlockActivity` | DONE |
| Implicit intents | VPN settings with fallback; PIN-gated CSV `ACTION_SEND` + FileProvider | DONE — built; chooser device check pending |
| Localization (English and Bangla) | `res/values/`, `res/values-bn/` | DONE |

## Week 9: UI, RecyclerView, Custom Views, UX

| Topic | Where | Status |
|---|---|---|
| RecyclerView + adapter + ViewHolder (query history) | `J/ui/QueryAdapter.java` | DONE |
| Second RecyclerView (per-app blocking list) | `J/ui/AppBlockAdapter.java` | DONE |
| Custom view (drawn on a Canvas) | `J/ui/TrendView.java` | DONE |
| UX: live status, block or allow colors, empty states | `MainActivity` | DONE |

## Week 10: Services, Broadcast Receivers, Content Providers

| Topic | Where | Status |
|---|---|---|
| VPN Service (`VpnService`) | `J/vpn/DnsVpnService.java` | DONE |
| Broadcast Receiver (boot) | `J/boot/BootReceiver.java` | DONE |
| Dynamic Broadcast Receiver (new app installed) | `DnsVpnService` package receiver; `new_app` parent feed | DONE — built; real install/update checks pending |
| Content Provider (read-only query log) | `J/log/QueryLogProvider.java` | DONE |
| Notifications and channels | `J/ui/Notifications.java` | DONE |
| Runtime permission flow (VPN consent, notifications) | `MainActivity` | DONE |

## Week 11: Networking, Data Handling, Advanced APIs, Firebase

| Topic | Where | Status |
|---|---|---|
| Socket programming (UDP DNS forwarding) | `blocklist/.../net/DnsForwarder.java` | DONE |
| Binary protocol parsing (DNS, IPv4/IPv6/UDP) | `blocklist/.../net/DnsMessage.java`, `IpPacket.java` | DONE |
| Text parsing (hosts-file lists) | `blocklist/.../HostListParser.java` | DONE |
| HTTP request + JSON parsing | `ListUpdater` HTTPS; `RulesJson` validated backups and document picker | DONE — built and JVM tested; device network checks pending |
| XML parsing | none yet | PLANNED (optional) |
| Room database (entity, DAO, batched writer) | `J/log/TracewallDb.java`, `QueryDao.java`, `HistoryWriter.java` | DONE |
| Background work (`WorkManager` periodic list refresh) | `ListRefreshWorker`, CONNECTED, unique KEEP, exponential retry | DONE — built; OS scheduling checks pending |
| Advanced APIs: `VpnService`, `protect()`, `getConnectionOwnerUid`, `ConnectivityManager` callbacks | `DnsVpnService`, `UidAttributor.java` | DONE |
| Firebase Auth, Realtime DB, Firestore | `FirebaseSync`, `FirebaseParent`, scoped pairing rules | DONE — built + 70 emulator checks; production end-to-end verification pending |

## Java concepts (Weeks 1 to 4, reused in the Android project)

| Topic | Where | Status |
|---|---|---|
| OOP, packages, interfaces, exceptions | `blocklist/` module | DONE |
| Multithreading, synchronization | `HistoryWriter` thread, `QueryLog` (synchronized), DNS worker pool | DONE |
| Unit testing (pure JVM) | `blocklist/src/test` | DONE — 98 passing tests |
| Git history | `git log` | DONE: keep committing small changes |

## Week 13 demo checklist

| They will ask about | Answer, in one line |
|---|---|
| Architecture | Pure-JVM engine (`:blocklist`) behind a child app (`:app`) and a parent app (`:parent`). |
| UI | RecyclerView history, custom trend view, per-app block list, Bangla and English. |
| Data flow | App DNS query goes through the TUN, then policy engine, then block or forward, then Room, then Firebase to the parent. |
| APIs and components | VpnService, Service, Receiver, ContentProvider, Room, Firebase, UDP sockets. |
| Design decisions | Route only `10.66.0.1/32` so a bug cannot break the whole network; precedence: allow beats app block beats category. |
| Report | [REPORT.md](REPORT.md): architecture, component/API table, decisions, tests, pending device checklist, limitations, future work. |

## Remaining verification

Implementation for tasks A–H builds. Runtime checks remain in [REPORT.md](REPORT.md):
rotation and scroll restoration, sharing/document-picker permissions, settings fallback,
live HTTP updates/atomic interruption, WorkManager scheduling/retry, actual install vs
update broadcasts, and real Firebase pairing/rules/history/revocation across two devices.
ADB found no attached device; the read-only API 37 AVD could not start because the host
had only 1.4 GB free disk space. No production rules were deployed or credentials changed.
XML parsing remains optional and is not claimed as implemented.
