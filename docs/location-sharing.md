# Latest-only child location sharing (development branch)

Branch: `dev/osmdroid-child-tracking`

## Scope and consent

This feature is deliberately **not** silent tracking. The child opens Family →
Location sharing, approves start with the existing parent PIN, grants Android
location and notification permissions, and starts a visible foreground service.
The notification has a Stop action. Tracking never starts at boot and stops when
cloud mode or the bound child identity becomes unavailable.

Only the latest point is stored. Tracewall does not create a route history. The
child requests Android location updates at approximately 30 seconds / 25 metres,
and overwrites:

```text
devices/{childUid}/location/current
```

The strict document contains only `latitude`, `longitude`, `accuracy_m`,
`time_ms`, `provider`, and `mock`. Stopping requests deletion of the document.
If the client is offline, deletion may remain queued; the parent labels snapshots
older than five minutes as stale instead of presenting them as live.

## Access control

- The child identity alone may create, update, or delete its point.
- The child and a parent with a current Firestore membership may read it.
- A parent cannot write or delete location data.
- Unpaired and signed-out users cannot read it.
- Revocation immediately removes the parent's rule-authorized read access.
- The bounded cloud-deletion flow now covers location as its fourth collection.

The checked-in Firestore rules implement these constraints. They are **not**
deployed automatically. The Firebase emulator suite exercises valid coordinates,
field/type bounds, unknown fields, parent read-only access, stranger denial, and
post-revocation denial.

## Android implementation

The child uses platform `LocationManager`, avoiding another proprietary location
SDK. `LocationSharingService` is a foreground service with the Android `location`
service type. It requires coarse or fine location and a visible notification;
background-location permission is not requested. A recent last-known fix may seed
the first update only when it is no more than two minutes old.

The parent uses `org.osmdroid:osmdroid-android:6.1.20` to display one marker on
OpenStreetMap tiles, with an explicit package user agent and OpenStreetMap
attribution. The map lifecycle follows Activity resume/pause/destroy and does not
prefetch or retain a route.

osmdroid is Apache-2.0 open source, but its upstream repository was archived on
20 November 2024. Version 6.1.20 is the final Maven Central release. This branch
pins that version and treats migration to a maintained renderer as future work.

## ShoreLine reference boundary

The supplied `AndroidAPP-ShoreLine--main(1).zip` informed the high-level flow:
permission launcher, foreground location service, Firebase listener, osmdroid
configuration, marker updates, and map lifecycle callbacks. Tracewall does not
copy ShoreLine's broad journey profile, hard-coded Firebase URL, high-frequency
telemetry, route breadcrumbs, UI language, or visual system. The implementation
uses Tracewall's existing verified pairing, cloud privacy mode, PIN gate, resources,
and strict data validation.

## Verification

- 237 core JVM tests pass, including six `LocationSnapshotTest` cases.
- 11 child, 6 parent, and 52 auth tests pass: 306 JVM tests total.
- 22 Python contracts pass.
- Both debug APKs assemble; child, parent, and auth lint report zero errors.
- 128 Firebase emulator REST checks pass on an isolated local port.
- On the Samsung SM-A127F, the English child screen, runtime permission flow,
  foreground notification, Stop action, and service teardown were verified. The
  parent osmdroid screen loaded OpenStreetMap tiles and visible attribution.
- Production rules have not been deployed and a physical latest-point upload/map
  marker round trip remains pending until the project owner explicitly authorizes
  a rules deployment. The currently deployed rules rejected the new path as
  expected; no production security configuration was changed.
