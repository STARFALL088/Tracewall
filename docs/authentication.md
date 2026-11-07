# Authentication and account recovery

Both Android apps use the shared Java `:auth` library for email/password only.
Google sign-in and phone-number OTP are intentionally not exposed or implemented.
Phone OTP was removed on 2026-11-07 after the Firebase project turned out to have
billing disabled; the removal note below records the decision.

## Identity policy

Firebase UIDs currently identify protected devices and parent memberships. A login
method must not silently replace one of those identities.

- Existing anonymous/named sessions add an email/password credential with
  `linkWithCredential`. Successful linking keeps the UID, device paths and parent
  memberships.
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
- Parent and Child must use different Firebase identities. One Firebase account
  used in both apps is one UID; it cannot parent itself. Reusing one
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

Do not paste API keys, OAuth tokens, signing passwords or refreshed configuration
into source control or logs. The existing build-fixture generator never reads or
overwrites real configuration files.

### Removed methods

The shared auth UI does not call Google sign-in, Credential Manager, phone
verification, SMS retrieval or any one-time-code API. It requests no SMS-reading,
contacts-reading or phone-number-reading permissions. Enabling a provider in the
Firebase console does not add client code back; the source and resource contracts
in `scripts/ci/test_auth_contract.py` fail if the phone or Google controls return.

A previous phone attempt is recorded here only as history: the project's SMS region
allowlist contained `BD`, but Firebase returned `17499 BILLING_NOT_ENABLED` because
the project has no billing account (`billingEnabled: false`). No SMS was sent. The
owner chose to remove phone login rather than add a payment method.

## Privacy and authorization

Authentication selects the Firebase principal; it does not introduce a new
history-sharing or account-deletion policy. Existing cloud behavior and the
independent app-name-sharing preference remain separate concerns. No optional
app-name-sharing setting is enabled by signing in.

Provider credentials do not grant access to another child's data. Parent membership
still requires the existing pairing workflow and server rules. Server rule files
are not weakened to permit login methods. Parent views discard prior
session feed state and verify access before attaching cloud listeners.

## Verification

Run the shared authentication and existing pure-JVM tests, source/resource
contracts, both debug builds and lint:

    python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
    ./gradlew :blocklist:test :auth:testDebugUnitTest \
      :app:testDebugUnitTest :parent:testDebugUnitTest \
      :app:assembleDebug :parent:assembleDebug \
      :auth:lintDebug :app:lintDebug :parent:lintDebug \
      --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m --console=plain

CI uses synthetic `demo-tracewall` configuration for compilation, not production
credentials. Synthetic build configuration is not an auth emulator connection.
Robolectric tests simulate Android preference/UI/lifecycle behavior on the JVM, not
physical-device dispatch or remote provider verification. Record real-device/provider
checks separately; they require the setup and user-controlled accounts above.

The separate SDK recovery/linking suite uses a real local Auth emulator:

    FIREBASE_CLI_DISABLE_USAGE=true firebase emulators:exec \
      --config firebase/firebase.json --project demo-tracewall --only auth \
      './gradlew :auth:testDebugUnitTest -PauthEmulator=true --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m --console=plain'

It creates fictional emulator accounts, never production accounts or real messages.
Tests assert real SDK guest-UID preservation, credential-collision retention,
wrong-candidate exclusion from primary auth, same-UID promotion/token refresh,
stale-operation rejection and secondary-session cleanup.

### Verified local checkpoint — 2026-11-07

- 258 regular JVM tests passed with zero failures or skips: 189 core, 11 Child,
  6 Parent and 52 auth. All 22 Python contract/integration tests passed.
- Both debug APKs built. Installed hashes match the local artifacts:
  Child `35486a190f0f91986f461fe1e2c144266433b30c0134cfd5a64f28e419b98ae2`,
  Parent `ecb2e2d5516d7d060c351e03576a55353e07665680e4dde9f93d00dd7022aace`.
- Auth, Child and Parent lint completed with zero errors (7 auth, 103 Child and
  40 Parent warnings; the Child/Parent totals include dependency and asset notices).
- English/Bengali auth resources match: 62 strings and no plural resources. The
  shared layout has no phone, code, SMS-consent, cooldown or resend controls, and
  the auth sources contain no phone/OTP symbols or `AccountPolicy.PHONE`.
- On the physical SM-A127F (Android 13), the Parent account screen rendered the
  guest identity with only the Email method. The email page offered
  `Link email/password`; an invalid address produced the localized validation
  message and cleared the password field. No crash, ANR or phone control was
  observed. A real email link was not attempted because no account credentials
  were supplied for this audit.
- The local Auth-emulator suite was not rerun because the phone removal only
  deleted credential types; the remaining email/recovery paths and their emulator
  tests are unchanged. No Firebase Console setting, production account, app data
  or signing configuration was changed.

## Primary references

- Firebase account linking: https://firebase.google.com/docs/auth/android/account-linking
- Firebase same-project user promotion: https://firebase.google.com/docs/reference/android/com/google/firebase/auth/FirebaseAuth#updateCurrentUser(com.google.firebase.auth.FirebaseUser)
- Firebase anonymous-account upgrades: https://firebase.google.com/docs/auth/android/anonymous-auth
- Firebase email/password: https://firebase.google.com/docs/auth/android/password-auth
- Robolectric setup: https://robolectric.org/getting-started/
