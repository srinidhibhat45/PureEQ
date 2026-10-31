# Google Play launch checklist

PureEQ goes to Play as the `play` flavour: the same app and package (`app.pureeq`) as the GitHub build, minus the self-updater. Positioning: a personal-sound app that tunes your headphones to your ears and taste, not a medical device. Nothing has been submitted yet.

## Decisions (owner, 2026-10-31)

- **Account:** an older personal developer account (created before 13 November 2023), so no 14-day closed test: internal testing, then production.
- **Signing:** B below. Google generates and keeps the app signing key; uploads are signed with an upload key you create (Google can reset a lost upload key). Moving a phone between the GitHub and Play builds means exporting profiles, reinstalling and importing.
- **Launch build:** 1.6.0 with per-output switching (1.5.3), volume-linked bass, an accessibility pass, translations (Spanish, German, French, Portuguese, Hindi) and the screenshots and graphics in [store/](store/).

## Timeline for a November 2026 launch

With the account above, the closed test below doesn't apply: internal testing for a few days, then a production release, which Google reviews (new apps can take several days). The rest of this section is for accounts that need it.

A personal developer account created after 13 November 2023 gets production access only after a closed test with at least 12 opted-in testers for 14 days (Play Console Help, checked 2026-10-31), so the dates hang on when that test starts:

1. **First week of November:** the Play Console account and its identity verification, the signing decision (0) and the upload key, the privacy policy at a public URL, the listing and App content (3, 4) filled in, the first bundle on internal testing.
2. **By about 7 November:** the closed test is live with at least 12 testers opted in. Later builds go to the same track during the 14 days; the clock doesn't restart.
3. **About 21 November:** apply for production access, then wait for Google's review of the application.
4. **Late November:** the production release, after Google's review of the app itself (new apps can take several days).

Organization accounts, and personal accounts created before 13 November 2023, skip the closed test: internal test, then production, so November is easy.

## 0. Decide first: one signing key for both builds?

Android only lets one build replace the other when both are signed with the same key (or a lineage from it). Choose before the first upload; changing Play's app signing key later goes through Play's key upgrade.

- [ ] **A. Shared key.** In Play Console's app signing setup, choose to use your own app signing key and upload the release key from [key-rotation.md](key-rotation.md) (Play Console walks you through its PEPK export tool). Play-installed and GitHub-installed copies then replace each other like any update, as long as the versionCode doesn't go down (a GitHub copy still on the debug key first needs one release-key GitHub update). Google recommends a separate upload key: create one with keytool and sign the bundle with it by setting the `PUREEQ_*` variables for that build (they take precedence over `keystore.properties`).
- [ ] **B. Separate keys.** Let Google generate the app signing key (the default) and sign uploads with the release key. Simplest, but moving between the GitHub and Play builds then means uninstalling first (profiles come back only through Android's backup or a profile export).

## 1. Build the upload

- [ ] Create the upload key (decision B), on your computer and never in chat or in the repository, then keep a backup offline like the release key in [key-rotation.md](key-rotation.md#2-back-it-up-offline):

  ```bash
  keytool -genkeypair -v -keystore ~/pureeq-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
  ```

  Play refuses debug-signed bundles, so build the Play bundle with that key in the `PUREEQ_*` variables, set only in the terminal that builds it (they take precedence over `keystore.properties`; the GitHub build keeps its own key). `read -s` asks for the password without echoing it or leaving it in the shell history:

  ```bash
  export PUREEQ_KEYSTORE=~/pureeq-upload.jks PUREEQ_KEY_ALIAS=upload; read -s "PUREEQ_KEYSTORE_PASSWORD?Upload key password: "; export PUREEQ_KEYSTORE_PASSWORD PUREEQ_KEY_PASSWORD=$PUREEQ_KEYSTORE_PASSWORD
  ```

  ```bash
  ./gradlew :app:testPlayDebugUnitTest :app:bundlePlayRelease
  ```

  Close that terminal afterwards, so a later `python3 ota/publish.py` can't sign the GitHub build with the upload key.

  On the first upload Play Console asks how to sign: keep **Let Google create and manage my app signing key**.
- [ ] Build and test:

  ```bash
  ./gradlew :app:testPlayDebugUnitTest :app:bundlePlayRelease
  ```

  Upload `app/build/outputs/bundle/playRelease/app-play-release.aab`.
- [ ] Check what the Play build contains (`./gradlew :app:assemblePlayRelease`, then `"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump permissions app/build/outputs/apk/play/release/app-play-release.apk`): MODIFY_AUDIO_SETTINGS, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, INTERNET, plus AndroidX's own DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No REQUEST_INSTALL_PACKAGES, UPDATE_PACKAGES_WITHOUT_USER_ACTION or ACCESS_NETWORK_STATE, no update receiver or job, no update checks, and Settings has no Updates section.
- [x] Checked 2026-10-31 (1.5.3): `lintPlayRelease` passes; targetSdk 36 meets Play's level for new apps (from 31 August 2026); the only native code, AndroidX's `libandroidx.graphics.path.so`, is 16 KB aligned on all four ABIs (`llvm-readelf -lW`: LOAD align 0x4000), as Play requires for apps targeting Android 15+; the debug-only output hook isn't in the release dex.
- [ ] Version: `versionCode` comes from `version.properties`, shared with the GitHub build. Upload each release to Play with the same versionCode as its GitHub twin, so phones can move between them without a downgrade.

## 2. Developer account and testing

- [ ] Identity verification of the Play developer account. The same account registers the GitHub build for developer verification ([key-rotation.md](key-rotation.md#7-register-apppureeq-for-developer-verification)).
- [ ] Personal developer accounts created after 13 November 2023 must run a closed test with at least 12 opted-in testers for 14 days before they can apply for production access.
- [x] The in-app wording matches the positioning, since reviewers see the app and its screenshots: since 1.5.0 the screens say "listening check" and "Ear tuning", hearing terms appear only in the disclaimers ("not a hearing test or an audiogram", "Not a medical device"), and the Play build's Privacy text leaves out the update checks (`BuildConfig.SELF_UPDATER`). Checked again for 1.5.3. Other brands appear only as compatibility notes (turn off Samsung's or Dolby's EQ, the Samsung 9-band export, Samsung Music in "Music apps").

## 3. App content declarations

### Foreground service: special use

- [ ] Declare `FOREGROUND_SERVICE_SPECIAL_USE` for `EqService` (the manifest subtype says "Keeps the user's personal equalizer applied to system audio output"). Description:

  > PureEQ applies the user's personal sound profile, an equalizer, to all audio the phone plays. When the user turns on "Apply to all audio" in the app or with PureEQ's Quick Settings tile, a foreground service attaches Android's DynamicsProcessing audio effect to the output mix (or, in "Music apps" mode, to the audio sessions music players open) and keeps it applied while the user listens in other apps. The user chooses on or off for each audio output (profiles are tuned for headphones, so the phone speaker starts off): when media moves to an output the user has it off for, the service releases the effect and waits, and when headphones it is on for come back it applies the effect again. The effect is released as soon as the service stops. A persistent notification shows the active profile or which outputs it is waiting for, with a Turn off button; the user can also turn it off in the app or from the tile. None of the predefined types fits: the service doesn't play, record or cast media, sync data or track location. It only keeps the system-wide effect alive and follows the audio output.

  Impact if the system stopped or deferred it:

  > The equalizer would switch off shortly after the user left PureEQ, so music in other apps would play without their sound profile, and it couldn't follow the user between their headphones and the phone speaker.

- [ ] Video (screen recording with sound, about a minute, link that anyone can open, such as an unlisted YouTube video):
  1. PureEQ open with a saved profile; tap **Apply to all audio** (allow notifications when asked).
  2. The PureEQ notification appears, naming the profile and the output.
  3. Open a music app, play a song, go to the home screen: PureEQ is not on screen, the notification stays, and the song plays with the profile.
  4. Pull down Quick Settings and switch the PureEQ tile off and on: the sound changes back and forth.
  5. Tap **Turn off** in the notification: the notification disappears and the effect is gone.

  The emulator can't connect headphones, so record this on a phone with the profile's headphones; unplugging them midway (the notification switches to "PureEQ is off for the phone speaker") shows the service following the output.

### Data safety

- [ ] Data collected or shared: **No**.
  - Profiles, listening-check and taste-test answers, headphone choices and settings are stored only on the phone; a song picked for the taste test is read on the phone.
  - Microphone (RECORD_AUDIO): only the optional room check, 3 seconds of measuring background noise, processed on the phone; nothing is recorded, stored or sent. Data handled only on the device doesn't count as collected.
  - Network, Play build: only downloads of AutoEq's public headphone index and one model's measurement files (cdn.jsdelivr.net, falling back to raw.githubusercontent.com) when the user looks up a model. No user data is sent; the servers see what any web request carries (IP address, a user agent naming the app and Android versions), and nothing reaches the developer.
  - Android Auto Backup copies the app's data into the user's own Google account; the developer can't read it. Check Play's current Data safety help that it needs no disclosure.
- [ ] Security: all traffic is HTTPS (cleartext is disabled), no account exists, and uninstalling deletes everything; profiles can also be deleted in the app.

### Health apps

- [ ] Declare in line with the positioning: **My app doesn't provide any health features**. Don't pick a Medical category or "Medical Device Apps".
- [ ] Put this sentence in the full description and keep it in the app's "Not a medical device" section: "PureEQ is not a medical device and does not diagnose, treat, cure, or prevent any medical condition."
- [ ] Risk: the declaration lists "hearing care" under Diseases and Conditions Management. A reviewer who reads the listening check as a hearing feature may ask for that category, which contradicts the positioning, so keep the listing, screenshots and app wording free of the words listed under the store listing.

### Privacy policy

- [ ] Fill in the contact address in [privacy-policy.md](privacy-policy.md), publish it at a public URL (for example a page in the public `pureeq-updates` repository or GitHub Pages) and enter that URL under App content and in the store listing.

### Content rating, audience, ads

- [ ] Content rating (IARC questionnaire): an app, not a game; no violence, sexual content, profanity, drugs, gambling or horror; no user-generated content or user-to-user communication; no location sharing; no purchases. Expect the lowest rating everywhere.
- [ ] Target audience: 18 and over. The app isn't designed for children, and including ages under 13 brings in the Families policy.
- [ ] Ads: none. App access: everything works without an account. News, government, financial features, health research: no.

## 4. Store listing draft

Category: Music & Audio. Contact email: the same address as the privacy policy.

**App name** (30 characters max): PureEQ: Personal Sound

**Short description** (80 max):

> Tunes your headphones to your ears and taste with a personal sound profile.

**Full description:**

> PureEQ tunes your headphones to your ears and taste.
>
> Make your personal sound profile in about five minutes:
>
> • Listening check: short beeps in each ear find which frequencies come through clearly with these headphones and which need a lift.
> • Taste test: blind A/B comparisons, matched in loudness, find the bass and treble you prefer, on the built-in track or a song of your own.
> • Your sound: both become one smooth EQ curve per ear. Fine-tune bass, warmth, vocals, treble and balance, and compare EQ on and off at the same loudness.
> • Bass that follows the volume: turn down from the volume you tuned at and PureEQ adds back the bass your ears lose at lower levels.
>
> Optionally pick your headphone model from the open-source AutoEq database of over 6,000 measured headphones, and PureEQ corrects that model's measured response as well.
>
> Use your profile everywhere:
> • Apply it to all audio on the phone, or only to music apps.
> • Switch it on and off from a Quick Settings tile.
> • Profiles follow your headphones: connect a pair and its profile takes over. PureEQ keeps on or off for each output, so the phone speaker plays untouched.
> • Export it in AutoEq's formats for other equalizer apps on Android and on your computer.
> • In English, Spanish, German, French, Portuguese and Hindi, and made to work with TalkBack and large text.
>
> Private by design:
> • Everything runs on your phone. No account, no ads, no analytics.
> • The optional room check listens for 3 seconds to measure background noise; nothing is recorded or stored.
> • PureEQ only goes online to download headphone data from AutoEq when you look up your model.
>
> PureEQ is not a medical device and does not diagnose, treat, cure, or prevent any medical condition.

**Never in the listing, screenshots or release notes:** "hearing test", "hearing loss", "hearing aid", "audiogram", "dB HL", "screening", "clinically validated", and Apple or Samsung product names. Use "personal sound profile", "listening check" and "tunes your headphones to your ears and taste".

**Graphics:** in [store/](store/): `icon-512.png` (512 × 512) and `feature-graphic-1024x500.png`, drawn by `store/art.py` from the launcher icon, and seven phone screenshots in `store/screenshots/` (1080 × 2160, or 1080 × 1500 for the listening profile, which is cropped above its reliability card; upload them in their numbered order). Taken on the emulator with neutral headphone names and checked against the list above. They show the English app; the translated listings can use them too.

**Translated listings:** `store/listing-<language>.md` has the app name, short description and full description in Spanish, German, French, Portuguese (Brazil) and Hindi; add each as a translation in Play Console › Store presence › Main store listing.

## 5. Release

- [ ] Internal testing track first; read the pre-launch report.
- [ ] Closed test (see 2), then apply for production.
- [ ] Each later release: publish the GitHub build (`python3 ota/publish.py`, which may raise the versionCode), then build the Play bundle (`./gradlew :app:bundlePlayRelease`) so it gets the same versionCode, and upload it.
