# Tracewall: CSE 2200 Syllabus Coverage

Mapped to the Advanced Programming Laboratory 2026 lecture plan (Weeks 8 to 13) and the Week 13 demo checklist.
Status: DONE = in the repo and verified on a device. PARTIAL = exists but incomplete or not device-verified. PLANNED = not yet in the repo.

Paths are relative to the repo root; `J/` means `app/src/main/java/tracewall/child`.

## Week 8: Android Studio, Manifest, Lifecycle, Intents

| Topic | Where | Status |
|---|---|---|
| Project structure, Gradle (3 modules: `:blocklist`, `:app`, `:parent`) | `settings.gradle`, `*/build.gradle` | DONE |
| AndroidManifest, permissions, `exported` flags | `app/src/main/AndroidManifest.xml` | DONE |
| Activity lifecycle callbacks | `J/MainActivity.java` (`onCreate`, `onResume`, `onPause`) | PARTIAL: add `onSaveInstanceState` and log all callbacks |
| XML layouts, view binding | `app/src/main/res/layout/` | DONE |
| Click listeners / event handling | `J/MainActivity.java`, `J/ui/AppBlockActivity.java` | DONE |
| Explicit intents | `MainActivity` to `AppBlockActivity` | DONE |
| Implicit intents | VPN settings (`ACTION_VPN_SETTINGS`), CSV share (`ACTION_SEND`) | PLANNED |
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
| Foreground Service (`VpnService`) | `J/vpn/DnsVpnService.java` | DONE |
| Broadcast Receiver (boot) | `J/boot/BootReceiver.java` | DONE |
| Dynamic Broadcast Receiver (new app installed) | planned in `DnsVpnService` | PLANNED |
| Content Provider (read-only query log) | `J/log/QueryLogProvider.java` | DONE |
| Notifications and channels | `J/ui/Notifications.java` | DONE |
| Runtime permission flow (VPN consent, notifications) | `MainActivity` | DONE |

## Week 11: Networking, Data Handling, Advanced APIs, Firebase

| Topic | Where | Status |
|---|---|---|
| Socket programming (UDP DNS forwarding) | `blocklist/.../net/DnsForwarder.java` | DONE |
| Binary protocol parsing (DNS, IPv4/IPv6/UDP) | `blocklist/.../net/DnsMessage.java`, `IpPacket.java` | DONE |
| Text parsing (hosts-file lists) | `blocklist/.../HostListParser.java` | DONE |
| HTTP request + JSON parsing | blocklist refresh from a URL, rules import and export | PLANNED |
| XML parsing | none yet | PLANNED (optional) |
| Room database (entity, DAO, batched writer) | `J/log/TracewallDb.java`, `QueryDao.java`, `HistoryWriter.java` | DONE |
| Background work (`WorkManager` periodic list refresh) | none yet | PLANNED |
| Advanced APIs: `VpnService`, `protect()`, `getConnectionOwnerUid`, `ConnectivityManager` callbacks | `DnsVpnService`, `UidAttributor.java` | DONE |
| Firebase Auth, Realtime DB, Firestore | `J/sync/FirebaseSync.java`, `parent/.../FirebaseParent.java` | PARTIAL: compiles, not verified end to end |

## Java concepts (Weeks 1 to 4, reused in the Android project)

| Topic | Where | Status |
|---|---|---|
| OOP, packages, interfaces, exceptions | `blocklist/` module | DONE |
| Multithreading, synchronization | `HistoryWriter` thread, `QueryLog` (synchronized), DNS worker pool | DONE |
| Unit testing (77 tests, pure JVM) | `blocklist/src/test` | DONE |
| Git history | `git log` | DONE: keep committing small changes |

## Week 13 demo checklist

| They will ask about | Answer, in one line |
|---|---|
| Architecture | Pure-JVM engine (`:blocklist`) behind a child app (`:app`) and a parent app (`:parent`). |
| UI | RecyclerView history, custom trend view, per-app block list, Bangla and English. |
| Data flow | App DNS query goes through the TUN, then policy engine, then block or forward, then Room, then Firebase to the parent. |
| APIs and components | VpnService, Service, Receiver, ContentProvider, Room, Firebase, UDP sockets. |
| Design decisions | Route only `10.66.0.1/32` so a bug cannot break the whole network; precedence: allow beats app block beats category. |
| Report | `docs/REPORT.md` (to write). |

## Gaps to close, in order of value

1. Implicit intents: CSV export via `ACTION_SEND` and a settings shortcut via `ACTION_VPN_SETTINGS`.
2. HTTP + JSON: refresh blocklists with `HttpURLConnection` on a background thread, plus JSON import and export of rules.
3. `WorkManager`: periodic blocklist refresh with a network constraint.
4. Dynamic receiver: warn when a new app is installed.
5. Lifecycle: `onSaveInstanceState`, and a rotation test.
6. Project report in `docs/REPORT.md`.
