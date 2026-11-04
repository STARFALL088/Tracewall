# Authentication and account recovery

Both Android apps use the shared Java `:auth` library for email/password, Google
Credential Manager and phone-number OTP. Gmail addresses work through either
email/password or the Google provider; they are not a separate Firebase provider.

## Identity policy

Firebase UIDs currently identify protected devices and parent memberships. A login
method must not silently replace one of those identities.

- Existing anonymous/named sessions add credentials with `linkWithCredential`.
  Successful linking keeps the UID, device paths and parent memberships.
- Initial installs with no identity can sign in to an existing account, create
  an email/password account, explicitly choose a Firebase guest identity, or
  continue without an account. Child local filtering is not dependent on login.
- Each installation durably anchors its first UID. Recovery accepts only that
  identity. A mismatched recovery is rejected instead of attaching another
  account's cloud state. The old protection rules remain in place.
  Candidates are verified in an isolated named Firebase app without database
  clients; only a matching user is copied into primary auth. Checking after a
  primary sign-in is too late to protect queued SDK writes from the wrong UID.
- Credential collisions are not automatic account merges. A credential already
  associated with another UID cannot be attached by silently signing in as that
  user. Existing cloud data and memberships are not migrated by this release.
- There is no ordinary sign-out, account switching, unlinking or account deletion
  control. Those need a separate migration design that drains SDK work and
  handles policy ownership. Do not use clearing app data as an authentication
  workaround for a protected Child installation.
- Child account management requires the existing local parent PIN. Authentication
  does not replace this PIN, Android VPN approval or parent membership checks.
- Parent and Child must use different Firebase identities. One Google account
  used in both apps is one Firebase UID; it cannot parent itself. Reusing one
  account across multiple protected Child devices also aliases their device
  paths in the current model. Use one identity per protected Child device.
- Restoring a named Parent account restores its principal, not the local list of
  saved child names on a fresh installation. Enter a known child UID to reconnect
  and verify existing membership; there is no server-side child discovery index.

Background startup, package events, boot and protection events never create a new
anonymous identity. Guest sign-in is an explicit account-screen action. No account
or login failure must disable the VPN, erase local history or clear existing
managed rules.

## Provider setup in Firebase Console

These are administrator actions, not something a successful APK build enables.
No production provider configuration, rules deployment or billing change is made
by this feature's build/tests.

For the existing Firebase project, configure both Android registrations:

- `tracewall.child`
- `tracewall.parent`

Keep Anonymous enabled if the explicit guest option should work.

### Email/password

1. Enable Email/Password in Authentication's sign-in providers.
2. Check the project's password policy and email templates/action domains.
3. Exercise account creation, sign-in, verification email and password reset with
   accounts you control. Reset feedback does not disclose whether an address exists.

Passwords are not trimmed or stored by Tracewall. The UI distinguishes validation
for a new password from signing in with an existing password; Firebase remains the
final authority for the project's configured password policy.

### Google

1. Enable Google for Firebase Authentication and select the required support email.
2. Register the appropriate SHA-1 and SHA-256 signing fingerprints for both Android
   package registrations. Debug and release signing configurations are different.
3. Download refreshed configuration for each Android app, including the OAuth
   client information, into the corresponding ignored `google-services.json`.
4. The app resolves the generated `default_web_client_id` resource at runtime.
   It must be the web/server OAuth client ID, not an Android client ID.
5. Test the real account picker and cancellation on a supported device. A missing
   OAuth resource is reported as setup required, not fabricated as login success.

Do not paste API keys, OAuth tokens, signing passwords or refreshed configuration
into source control or logs. The existing build-fixture generator never reads or
overwrites real configuration files.

### Phone OTP

1. Enable Phone authentication and review the project's allowed SMS regions,
   quotas and current billing requirements before allowing real SMS delivery.
2. Register SHA-256 for Play Integrity and SHA-1 for the reCAPTCHA fallback. Keep
   the Firebase authorized domains/API configuration appropriate for that flow.
3. Use Firebase-configured fictional test numbers for initial provider testing.
   Do not guess verification codes or disable real-device app verification.
4. Verify explicit SMS/privacy consent, international E.164 input, automatic
   verification, manual code entry, timeout, cooldown/resend and cancellation.
5. Complete any real account challenge/reCAPTCHA/OTP yourself. Those actions and
   successful real SMS delivery cannot be inferred from builds or unit tests.

Phone-only authentication is weaker than the other offered methods because a
phone number can change owners or be accessible from another device profile.
Prefer linking a Google or email/password method too; login does not replace
the Child's independent local parent PIN.

The app does not request SMS-reading, contacts or phone-number permissions for
this feature. Phone-verification IDs and resend tokens stay in transient memory;
Firebase Authentication manages its own session in app-private storage. Password
and OTP controls must not save their contents into Activity state.

## Privacy and authorization

Authentication selects the Firebase principal; it does not introduce a new
history-sharing or account-deletion policy. Existing cloud behavior and the
independent app-name-sharing preference remain separate concerns. No optional
app-name-sharing setting is enabled by signing in.

Provider credentials do not grant access to another child's data. Parent membership
still requires the existing pairing workflow and server rules. Server rule files
are not weakened to permit the new login methods. Parent views discard prior
session feed state and verify access before attaching cloud listeners.

## Verification

Run the shared authentication and existing pure-JVM tests, source/resource
contracts, both debug builds and lint:

    python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
    ./gradlew :blocklist:test :auth:testDebugUnitTest \
      :app:assembleDebug :parent:assembleDebug \
      :auth:lintDebug :app:lintDebug :parent:lintDebug \
      --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m --console=plain

CI uses synthetic `demo-tracewall` configuration for compilation, not production
credentials. Synthetic build configuration is not an auth emulator connection and
cannot prove that a real Google chooser or SMS provider works. Robolectric tests
simulate Android preference/UI/lifecycle behavior on the JVM, not physical-device
dispatch or remote provider verification. Record real-device/provider checks
separately; they require the setup and user-controlled accounts above.

The separate SDK recovery/linking suite uses a real local Auth emulator:

    FIREBASE_CLI_DISABLE_USAGE=true firebase emulators:exec \
      --config firebase/firebase.json --project demo-tracewall --only auth \
      './gradlew :auth:testDebugUnitTest -PauthEmulator=true --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m --console=plain'

It creates fictional emulator accounts and phone-verification sessions, never
real SMS or production accounts. Tests assert real SDK guest-UID preservation,
credential-collision retention, wrong-candidate exclusion from primary auth,
same-UID promotion/token refresh, stale-operation rejection and secondary-session
cleanup. This is not a Google chooser, real SMS, Play Integrity or reCAPTCHA test.
CI runs this suite before the regular suite, with Java 21 for the pinned Firebase
CLI and Java 17 for Gradle. SDK reports are preserved in
`auth/build/test-results/authEmulator/` and `auth/build/reports/tests/authEmulator/`.

### Verified local checkpoint — 2026-11-04

- 245 regular JVM tests (188 core, 57 auth), 8 real-SDK/local-Auth-emulator tests,
  and 15 Python contract/integration tests passed without failures or skips.
- Both debug APKs built. Auth, Child and Parent lint passed with zero errors;
  warnings remain: 8 dependency-version advisories in auth, 30 Child, 20 Parent.
- Binary manifest checks confirm private account activities and no SMS-reading,
  contacts-reading or phone-number-reading permissions. EN/BN authentication
  resources match: 81 strings and one plural resource.
- Neither current app build has a generated `default_web_client_id`. Google
  setup is still required; enabling providers and installing refreshed configs
  is an administrator task, not an implemented fake login.
- No ADB device was attached at this checkpoint. The auth-enabled APKs have not
  been installed or physically exercised. Earlier DNS-fixed APK tests do not
  establish provider success or a completed same-phone parent/child round trip.

## Primary references

- Firebase Google/Java integration: https://firebase.google.com/docs/auth/android/google-signin
- Firebase account linking: https://firebase.google.com/docs/auth/android/account-linking
- Firebase same-project user promotion: https://firebase.google.com/docs/reference/android/com/google/firebase/auth/FirebaseAuth#updateCurrentUser(com.google.firebase.auth.FirebaseUser)
- Firebase anonymous-account upgrades: https://firebase.google.com/docs/auth/android/anonymous-auth
- Firebase phone authentication: https://firebase.google.com/docs/auth/android/phone-auth
- Firebase email/password: https://firebase.google.com/docs/auth/android/password-auth
- Android Credential Manager releases: https://developer.android.com/jetpack/androidx/releases/credentials
- Robolectric setup: https://robolectric.org/getting-started/
