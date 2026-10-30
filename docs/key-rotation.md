# Moving PureEQ from the debug key to a release key

Every PureEQ published so far (`dist/`, 1.0.0 to 1.4.0) is signed with this computer's debug key. That has to change: Google Play refuses debug-signed apps, Android developer verification needs a registered signing key (on certified devices from 2027, updates to unregistered apps fail), and the debug key exists only on this computer.

Android installs an update only if it is signed with the key of the installed copy, or with a newer key whose **lineage** (APK Signature Scheme v3 proof of rotation) contains that key. So the switch is: create a release key, create a lineage from the debug key to it, and sign every GitHub release from then on with the release key plus the lineage. PureEQ's own updater accepts such an update in every version that has one (1.1.0 and later); the decision is `Signers.accepts` in `app/src/github/java/app/pureeq/update/Signers.kt`, with unit tests.

Paths below are examples. Use absolute paths in `keystore.properties` (no `~`).

## How the build signs

- **Gradle** signs release builds of both flavours with the release key when one is configured, otherwise with the debug key as before:
  - environment variables `PUREEQ_KEYSTORE`, `PUREEQ_KEYSTORE_PASSWORD`, `PUREEQ_KEY_ALIAS`, `PUREEQ_KEY_PASSWORD` (used whenever any of them is set), or
  - `keystore.properties` at the repo root (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

  A half-filled configuration stops the build with the names that are missing, rather than quietly signing with the debug key.
- **`ota/publish.py`** builds the `github` flavour. When a lineage is configured (`lineage=` in `keystore.properties`, or `PUREEQ_LINEAGE`), it re-signs the APK with the release key and the lineage. Before publishing anything it checks that phones with the first and with the latest APK in `dist/` would accept the new one, and stops if not.

## 1. Create the release key (once)

```bash
mkdir -p ~/PureEQ-keys && chmod 700 ~/PureEQ-keys
keytool -genkeypair -v -keystore ~/PureEQ-keys/pureeq-release.jks -alias pureeq \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=<name shown in the certificate>"
```

keytool asks for the password, so it stays out of the shell history. Its default keystore type (PKCS12) uses the keystore password for the key as well, so `keyPassword` is the same as `storePassword`. Keep the keystore outside the repository; `.gitignore` also ignores `*.jks` and `*.keystore` as a safety net.

## 2. Back it up offline

Lose the release key and the GitHub build can never be updated again: every phone would need to uninstall it and install a new app. Before publishing anything signed with it:

- Copy `pureeq-release.jks`, the lineage file (step 3) and `~/.android/debug.keystore` (the old key, only needed to create the lineage again) to two offline places, for example an encrypted USB drive kept at home and one kept elsewhere.
- Store the keystore password apart from the files (a password manager, or on paper kept away from the drives).
- Never commit them, email them or paste them into chats or prompts.

## 3. Create the lineage from the debug key (once)

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"   # or the newest build-tools you have
"$BT/apksigner" rotate --out ~/PureEQ-keys/pureeq.lineage \
  --old-signer --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey \
  --new-signer --ks ~/PureEQ-keys/pureeq-release.jks --ks-key-alias pureeq
```

apksigner asks for the old keystore's password first (the debug keystore has Android's standard debug password; see "Sign your debug build" in the Android documentation), then the new one's. Check the result:

```bash
"$BT/apksigner" lineage --in ~/PureEQ-keys/pureeq.lineage --print-certs
"$BT/apksigner" verify --print-certs dist/PureEQ-1.4.0.apk
```

The lineage lists two certificates; the first one's SHA-256 digest must equal the digest printed for `dist/PureEQ-1.4.0.apk` (the debug key).

## 4. Configure this computer

`keystore.properties` at the repo root, readable only by you (`chmod 600 keystore.properties`):

```properties
storeFile=/Users/<you>/PureEQ-keys/pureeq-release.jks
storePassword=<keystore password>
keyAlias=pureeq
keyPassword=<same as storePassword for a PKCS12 keystore>
lineage=/Users/<you>/PureEQ-keys/pureeq.lineage
```

It is a Java properties file: a backslash escapes the next character, so avoid backslashes in values. To keep the passwords off disk, leave the file out and set `PUREEQ_KEYSTORE`, `PUREEQ_KEYSTORE_PASSWORD`, `PUREEQ_KEY_ALIAS`, `PUREEQ_KEY_PASSWORD` and `PUREEQ_LINEAGE` instead (for example from a password manager's command-line tool). The variables win over the file whenever any of the first four is set.

## 5. How publish.py signs from then on

`python3 ota/publish.py` (and `--dry-run`):

1. Gradle tests and builds the `github` release; Gradle signs it with the release key.
2. publish.py re-signs it with apksigner, the release key and the lineage. Passwords go to apksigner through environment variables, never on the command line. The equivalent command:

   ```bash
   "$BT/apksigner" sign --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
     --v4-signing-enabled false --rotation-min-sdk-version 28 \
     --lineage ~/PureEQ-keys/pureeq.lineage \
     --ks ~/PureEQ-keys/pureeq-release.jks --ks-key-alias pureeq \
     --out ota/out/PureEQ-<version>.apk app/build/outputs/apk/github/release/app-github-release.apk
   ```

   Every phone PureEQ supports (Android 9+) checks the v3 signature, which carries the lineage, and `--rotation-min-sdk-version 28` puts the rotated key there for Android 9 onwards (by default apksigner would use it only from Android 13). A v1 or v2 signature would also need the debug key to sign, so they are off and the debug key is not needed for releases.
3. publish.py checks the signed APK against the first and the latest APK in `dist/`: the same key, or a lineage containing theirs. Otherwise it stops before anything is published.
4. It writes `update.json` (size and SHA-256 of the final APK) and publishes as before.

Keep the lineage in every GitHub release from now on: a phone that skipped updates still has a debug-signed PureEQ. The check in step 3 enforces this.

Google's own example also passes the old key, for apps that support Android 8 and older. Should a phone refuse the v3-only APK in the tests below, that form signs v2 with the debug key and v3 with the release key and lineage (publish.py's `sign_with_lineage` would then need the same change):

```bash
"$BT/apksigner" sign --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey \
  --next-signer --ks ~/PureEQ-keys/pureeq-release.jks --ks-key-alias pureeq \
  --lineage ~/PureEQ-keys/pureeq.lineage --rotation-min-sdk-version 28 \
  --out ota/out/PureEQ-<version>.apk app/build/outputs/apk/github/release/app-github-release.apk
```

## 6. Test before the first release-key publish

Use emulators or spare phones, never your main phone first: one on Android 9 to 12 (API 28 and API 31 or 32 if you can) and one on Android 13 or later. Android 13+ honours rotation everywhere apps compare signatures; on 9 to 12, install-time rotation works but deserves the test.

**A. Android accepts the rotated update** (on each device):

1. `adb install dist/PureEQ-1.4.0.apk` (debug key), open it and save a profile.
2. With `keystore.properties` and the lineage in place: `python3 ota/publish.py --dry-run`. It signs `ota/out/PureEQ-<version>.apk` and runs the check from step 5.
3. `adb install -r ota/out/PureEQ-<version>.apk` must print `Success`, not `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Open the app: the profile is still there.
4. `adb shell dumpsys package app.pureeq | grep -i -A3 signatures` should show the release certificate and, as past signatures, both certificates.
5. `adb install -r dist/PureEQ-1.4.0.apk` (debug-signed) must now fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: Android doesn't go back to the old key.

**B. PureEQ's updater accepts it** (on each emulator; debug builds may fetch updates over plain http from this computer, which emulators reach as 10.0.2.2):

1. `./gradlew :app:assembleGithubDebug -Pota.manifestUrl=http://10.0.2.2:8765/update.json`, then install `app/build/outputs/apk/github/debug/app-github-debug.apk` (debug key).
2. Raise `versionCode` in `version.properties` by one (temporarily; don't commit it), build the same task again, and sign that APK with the step 5 command, taking `app/build/outputs/apk/github/debug/app-github-debug.apk` as input and `--out ota/out/test/PureEQ-test.apk`. Put `version.properties` back.
3. Write `ota/out/test/update.json`:

   ```json
   {"package": "app.pureeq", "versionCode": <raised code>, "versionName": "<name>",
    "url": "http://10.0.2.2:8765/PureEQ-test.apk", "sha256": "<shasum -a 256 of the APK>",
    "size": <stat -f%z of the APK>, "minSdk": 28}
   ```

4. `python3 -m http.server 8765 --directory ota/out/test`, then in the app *Settings › Updates › Check now*, then *Install*. It must download, pass the checks and install, keeping the profile. If Android doesn't report a download's certificates, PureEQ's own check steps aside and the system installer still decides.

**C. After the first release-key publish**, watch one real phone update itself, and keep the debug-signed `dist/` APKs: publish.py compares against them.

## 7. Register app.pureeq for developer verification

From Google's guide ([developer verification](https://developer.android.com/developer-verification/guides)): enforcement started on 30 September 2026 in Brazil, Indonesia, Singapore and Thailand and extends to all certified devices in 2027. An app that is distributed both on and outside Google Play is registered from the existing **Play Console** account, which gets the functions to register apps and keys distributed outside Play; an app only outside Play uses the Android Developer Console.

1. Complete the developer identity verification for the account.
2. Register the package name `app.pureeq` and prove you own it by providing an APK signed with your private key, as the console describes. Use the release key: every future GitHub update is signed with it.
3. Check in the console whether keys from the lineage (the debug key) or Play's app signing key need registering as well; the guide doesn't say how rotated keys are handled.
4. Do this before the global rollout reaches your users' phones.

A "limited distribution" account (up to 20 devices, no identity verification) exists for personal use; with a Play listing, register the GitHub build through Play Console as above instead.

## References

- apksigner (rotate, sign with `--lineage`, `--rotation-min-sdk-version`): <https://developer.android.com/tools/apksigner>
- Android developer verification: <https://developer.android.com/developer-verification/guides>
