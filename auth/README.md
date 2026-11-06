# Shared Tracewall authentication (`:auth`)

Java 17 Android library, min SDK 24 / compile SDK 34, namespace `tracewall.auth`.
It uses Firebase BoM **33.1.0**. It does not apply the Google Services plugin or
contain Firebase configuration. Supported remote methods are email/password and
phone OTP; Google sign-in is intentionally not exposed or implemented. The module
does not directly declare Google ID or Credential Manager dependencies; Firebase
Auth still brings its own transitive authentication support libraries.

## Caller contract

Each consuming app adds `implementation(project(":auth"))` and launches the private
manifest activity explicitly (use the Activity Result API if a result is needed):

```java
Intent intent = new Intent(this, tracewall.auth.AuthActivity.class)
        .putExtra(tracewall.auth.AuthActivity.EXTRA_ROLE,
                tracewall.auth.AuthActivity.ROLE_CHILD) // or ROLE_PARENT
        .putExtra(tracewall.auth.AuthActivity.EXTRA_INITIAL, true);
// If the host has an immutable durable installation UID, pass its EXACT value:
intent.putExtra(tracewall.auth.AuthActivity.EXTRA_BOUND_UID, boundUid);
launcher.launch(intent);
```

Public constants:

- `EXTRA_ROLE = "tracewall.auth.role"`, `ROLE_CHILD = "child"`, `ROLE_PARENT = "parent"`.
  An unknown/missing role is treated as Child. Roles are UI context, not server authorization.
- `EXTRA_INITIAL = "tracewall.auth.initial"`. A successful initial sign-in or anonymous
  sign-in returns `RESULT_OK` and finishes when this is true. An existing-account screen
  stays open after linking; Back returns the successful result if applicable.
- `EXTRA_BOUND_UID = "tracewall.auth.bound_uid"`, optional immutable installation anchor.
  This screen never replaces an existing durable anchor. The persisted `IdentityAnchor`
  takes precedence over the optional caller value. Nonempty values are compared exactly,
  without trimming or normalization.
- Continue locally returns `RESULT_CANCELED`, does not sign in, and does not create an
  anonymous account. The caller must show its local UI on cancellation, including offline.
  No credentials/tokens/user details are returned in an Intent.

The host must enforce its durable anchor in **every cloud facade/listener/publisher**.
Recovery candidates are verified in a separate named Firebase app with no database clients;
only the exact anchored UID is copied into primary auth. `EXTRA_BOUND_UID` is not a
substitute for facade guards against unexpected sessions or unbound startup races.
`IdentityAnchor.java` persists the first installation UID. Both application
facades and launchers integrate it; authentication never overwrites an existing anchor.

## Immutable identity and recovery policy

- Every non-null Firebase user, anonymous or registered, can only link additional
  providers. The same UID must remain after linking. Already-connected providers are
  disabled instead of replaced; email/password has a distinct same-account reauthentication
  action using the account's existing email, without changing credentials.
- Credential collisions never trigger a merge, primary sign-in, sign-out, delete,
  retry under another identity, or new guest. Existing device data/UID are preserved.
- **No normal primary sign-out or account switching is exposed for either role**, even
  registered Parent accounts. A safe quiesced migration design for offline cloud queues
  and principal-local profiles is outside this release.
- If a non-null user disagrees with the supplied anchor, all auth mutations are blocked.
  That existing session is never automatically switched or signed out.
- A missing bound session is recovered through an isolated named Firebase auth instance.
  A wrong candidate never becomes the primary principal, so queued database writes cannot
  run under that identity. An exact match is copied with `updateCurrentUser`, avoiding a
  second consumption of phone credentials. The secondary session is signed out/deleted;
  a fixed name bounds private SDK storage after interruption. No primary sign-out occurs.
- With a nonempty anchor, guest creation and email account creation are unavailable:
  recover the original linked account or continue locally. An anonymous account that was
  never linked cannot be recovered after losing its Firebase session. The library must not
  silently create a replacement anonymous UID.
- Use **separate Parent and Child accounts**, and **one account per protected Child
  device**. A Firebase UID cannot parent itself. Reusing one Child account across multiple
  protected devices is unsupported by the current device-keyed backend; this library
  warns about the restriction but does not introduce server-side multi-device modeling.

## Authentication and lifecycle behavior

Email login validates a nonempty untrimmed password; creating/linking requires at least
8 characters (server password policy can impose more). Gmail is accepted as an email
identifier; a password-provider password is not a Google account password. Reset feedback
avoids exposing whether an email address has an account. Verification email, verification
refresh, and email/password reauthentication are explicit actions.

Phone verification requires unchecked-by-default SMS/privacy/charges consent, E.164 syntax,
and exactly six ASCII code digits. No SMS/phone permissions are requested. Firebase handles
app verification/automatic retrieval. The model owns verification IDs and force-resend tokens
only in RAM; they are never stored in Bundles, files, preferences, or saved-state handles.
Activity restart reattaches SDK callbacks only inside the original active request window,
without a force-resend token. A stopped/expired request never auto-resends. Manual resend uses
the SDK resending token and a monotonic 60-second cooldown (including after cancel/change).
Process death intentionally loses the phone flow; start again explicitly and obey server limits.

The ViewModel owns asynchronous operations; callbacks never capture the Activity. Generation
and completion guards reject double submissions, stale callbacks, and duplicate auto/manual
phone completions. Firebase-owned tasks are not falsely presented as cancellable. Password
and code views disable state saving and clear on submit, stop, and state save. Authentication
screens set `FLAG_SECURE`. There are no raw exception, token, phone, password, or OTP logs.
The screen uses an explicit Back button, scrolling, NoActionBar, and matched English/Bengali
resource keys and format placeholders.

## Required Firebase/application setup (not performed by this change)

1. Register **both** actual Android application IDs in the intended Firebase project.
   Each application owns its Google Services configuration/plugin; obtain configuration
   through the administrator's normal process, never put private server keys in the app.
2. Enable Email/Password, Phone, and Anonymous providers as desired in Firebase
   Authentication. A provider not enabled fails safely with a localized explanation.
3. Add each app's actual debug/release signing **SHA-1 and SHA-256** fingerprints as
   required for Phone app verification.
4. Configure SMS regions, quotas/billing, and Play Integrity/reCAPTCHA requirements for
   the project. Do not disable app verification in production. For runtime QA, use only
   administrator-approved Firebase test numbers in a nonproduction project. Automated tests
   create only fictional local-emulator accounts, never production accounts or real SMS.
5. Keep backend pairing/rules authorization independent from this UI's role extra. Keep
   cloud networking/listeners subject to the apps' existing consent and identity guards.

Official implementation references:

- https://firebase.google.com/docs/auth/android/password-auth
- https://firebase.google.com/docs/auth/android/phone-auth
- https://firebase.google.com/docs/auth/android/account-linking

## Tests and remaining verification

`auth/src/test/java/tracewall/auth` contains deterministic JUnit 4 tests for input syntax,
whitespace-preserving passwords, provider decisions, UID invariants, collisions, disabled
normal sign-out for **both** roles, exact-bound recovery, narrowly scoped wrong-candidate
promotion prevention, blocked bound-guest replacement, SMS cooldown, generation ownership,
duplicate completions, and safe error classification. Regular helper/UI tests use synthetic
inputs and inert SDK configuration. There is no fake success path in production UI.

Pure helpers/tests can run with plain `javac --release 17` + JUnit 4.13.2.
`:auth:testDebugUnitTest` also runs Robolectric Android-preference/UI tests; run
both consumer APK builds and all lint tasks separately. Device/provider tests
remain distinct from JVM UI checks. See `../docs/authentication.md` for setup and
`../docs/UPDATE_PROGRESS.md` for measured verification results and deferrals.

`BoundAccountRecoveryEmulatorTest` separately runs the actual Android Firebase SDK against
a loopback `demo-tracewall` Auth emulator. It covers guest linking/collisions, exact/wrong
recovery, stale ownership, restored secondary sessions, immutable existing primary auth,
and phone-credential promotion. Run it with `-PauthEmulator=true` inside `emulators:exec`.
This mode cannot use Gradle up-to-date/build-cache results; XML/HTML reports are archived
under `auth/build/{test-results,reports/tests}/authEmulator` before normal tests run again.
