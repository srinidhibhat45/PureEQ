# Google Play launch checklist

PureEQ goes to Play as the `play` flavour: the same app and package (`app.pureeq`) as the GitHub build, minus the self-updater. Positioning: a personal-sound app that tunes your headphones to your ears and taste, not a medical device. Nothing has been submitted yet.

## Decisions

- **Account (owner, 2026-11-11):** a new personal developer account, not created yet. (On 2026-10-31 the plan assumed an older account; there is none.) A personal account created after 13 November 2023 must run a closed test with at least 12 testers opted in for 14 days in a row before it can apply for production.
- **Signing (2026-10-31):** B below. Google generates and keeps the app signing key; uploads are signed with an upload key you create (Google can reset a lost upload key). Moving a phone between the GitHub and Play builds means exporting profiles, reinstalling and importing.
- **Launch build:** 1.7.3 (versionCode 16): everything up to 1.6.0, listening modes with Auto (1.7.0, 1.7.2), shorter text (1.7.1) and the sound fixes (1.7.3), with the eight screenshots retaken for it on 2026-11-11 and the graphics in [store/](store/).

## Timeline from 11 November 2026

Checked against Play Console Help on 2026-11-11 (answers 14151465, 13628312, 14316361): at least 12 testers opted in continuously for the 14 days right before applying (testers who leave early don't count, and a tester who rejoins starts a new 14 days); Google usually decides on the application within 7 days.

1. **This week:** create the account (US$25, once), verify email and phone by code, verify your identity (government ID, plus proof of address in some countries) and an Android device through the Play Console app. Verification can take a few days; meanwhile make the upload key, publish the privacy policy, and line up at least 15 testers (a margin over 12, in case some drop out).
2. **As soon as the account is verified (about 15-18 November):** create the app, fill in App content (3) and the store listing (4), upload the 1.7.3 bundle to a closed testing track and send testers the opt-in link. The 14 days start when the twelfth tester has opted in. Later builds can go to the same track during the 14 days; the clock doesn't restart.
3. **About 2-4 December:** Dashboard › Apply for production; answer the three sections (closed test, app, readiness).
4. **About 9-11 December:** once approved, release to production; Google reviews the app itself (new apps can take several days), so expect PureEQ on Play in mid-December.

Organization accounts, and personal accounts created before 13 November 2023, skip the closed test: internal test, then production.

## 0. Decide first: one signing key for both builds?

Android only lets one build replace the other when both are signed with the same key (or a lineage from it). Choose before the first upload; changing Play's app signing key later goes through Play's key upgrade.

- [ ] **A. Shared key.** In Play Console's app signing setup, choose to use your own app signing key and upload the release key from [key-rotation.md](key-rotation.md) (Play Console walks you through its PEPK export tool). Play-installed and GitHub-installed copies then replace each other like any update, as long as the versionCode doesn't go down (a GitHub copy still on the debug key first needs one release-key GitHub update). Google recommends a separate upload key: create one with keytool and sign the bundle with it by setting the `PUREEQ_*` variables for that build (they take precedence over `keystore.properties`).
- [ ] **B. Separate keys.** Let Google generate the app signing key (the default) and sign uploads with an upload key you create (section 1). Simplest, but moving between the GitHub and Play builds then means uninstalling first (profiles come back only through Android's backup or a profile export).

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
- [x] Checked 2026-11-11 (1.7.3): the permissions are exactly the list above, no update code or debug output hook in the release dex, targetSdk 36, `libandroidx.graphics.path.so` 16 KB aligned on all four ABIs.
- [x] Checked 2026-10-31 (1.5.3): `lintPlayRelease` passes; targetSdk 36 meets Play's level for new apps (from 31 August 2026); the only native code, AndroidX's `libandroidx.graphics.path.so`, is 16 KB aligned on all four ABIs (`llvm-readelf -lW`: LOAD align 0x4000), as Play requires for apps targeting Android 15+; the debug-only output hook isn't in the release dex.
- [ ] Version: `versionCode` comes from `version.properties`, shared with the GitHub build. Upload each release to Play with the same versionCode as its GitHub twin, so phones can move between them without a downgrade.

## 2. Developer account and testing

- [ ] Create the account at play.google.com/console as **Yourself** (personal), pay the US$25 fee, and verify the contact email and phone by code. The developer name can differ from your legal name and can change later; Google Play shows your legal name, country and developer email publicly (your full address only if you sell something).
- [ ] Identity verification (government photo ID, and proof of address in some countries) and **Verify that you have access to an Android mobile device** (a Home task: install the Play Console app from its QR code on any physical, unrooted phone with Android 10 or later). The same account registers the GitHub build for developer verification ([key-rotation.md](key-rotation.md#7-register-apppureeq-for-developer-verification)).
- [ ] Closed test: Testing › Closed testing › create a track, add testers by email list (or a Google Group), upload the bundle, roll it out, and send testers the opt-in link from the track's Testers tab. Each tester needs a Google account on an Android phone, opens the link, taps **Become a tester**, then installs PureEQ from Play. Ask them to stay opted in for the full 14 days and to actually use it: the production application asks what testers did and what you changed.
- [ ] Apply for production from the Dashboard once at least 12 testers have been opted in for 14 days in a row.
- [x] The in-app wording matches the positioning, since reviewers see the app and its screenshots: since 1.5.0 the screens say "listening check" and "Ear tuning", hearing terms appear only in the disclaimers ("not a hearing test or an audiogram", "Not a medical device"), and the Play build's Privacy text leaves out the update checks (`BuildConfig.SELF_UPDATER`). Checked again for 1.5.3. Other brands appear only as compatibility notes (turn off Samsung's or Dolby's EQ, the Samsung 9-band export, Samsung Music in "Music apps").

## 3. App content declarations

### Foreground service: special use

- [ ] Declare `FOREGROUND_SERVICE_SPECIAL_USE` for `EqService` (the manifest subtype says "Keeps the user's personal equalizer applied to system audio output"). Description:

  > PureEQ applies the user's personal sound profile, an equalizer, to all audio the phone plays. When the user turns on "Apply to all audio" in the app or with PureEQ's Quick Settings tile, a foreground service attaches Android's DynamicsProcessing audio effect to the output mix (or, in "Music apps" mode, to the audio sessions music players open) and keeps it applied while the user listens in other apps. The user chooses on or off for each audio output (profiles are tuned for headphones, so the phone speaker starts off): when media moves to an output the user has it off for, the service releases the effect and waits, and when headphones it is on for come back it applies the effect again. The effect is released as soon as the service stops. A persistent notification shows the active profile or which outputs it is waiting for, with a Turn off button; the user can also turn it off in the app or from the tile. None of the predefined types fits: the service doesn't play, record or cast media, sync data or track location. It only keeps the system-wide effect alive and follows the audio output, and in Auto mode reads which kind of audio is playing (music, speech, a film or a game, as Android reports it) to pick the profile's listening mode.

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
  - Auto mode reads the type of audio playing (Android's usage and content type for each player, without the app's name), on the phone only.
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
> • Modes for what you listen to: Music plays your sound exactly as tuned, Voice clears up podcasts and audiobooks, Gaming brings footsteps forward with less delay, General suits a bit of everything, and Auto switches by itself to suit what's playing.
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

**Graphics:** in [store/](store/): `icon-512.png` (512 × 512) and `feature-graphic-1024x500.png`, drawn by `store/art.py` from the launcher icon, and eight phone screenshots in `store/screenshots/` (1080 × 2160; the listening profile 1080 × 1580, cropped below its reliability card so the not-a-hearing-test line stays out; Settings 1080 × 1750, framed below the line that names other EQs). Upload them in their numbered order: home with the modes, the mode question, the listening check, the listening profile, the taste test, your EQ, Fine-tune, Where it's on. Retaken 2026-11-11 on the emulator from the 1.7.3 Play debug build, with neutral headphone names, a simulated listener and a demo-mode status bar cropped off, and checked against the list above. They show the English app; the translated listings can use them too.

**Translated listings:** `store/listing-<language>.md` has the app name, short description and full description in Spanish, German, French, Portuguese (Brazil) and Hindi; add each as a translation in Play Console › Store presence › Main store listing.

## 5. Release

- [ ] Closed testing track with the 1.7.3 bundle; read the pre-launch report (Release › Testing › Pre-launch report) and fix anything it flags before testers' 14 days run out.
- [ ] Apply for production (see 2), then create the production release from the same bundle.
- [ ] Each later release: publish the GitHub build (`python3 ota/publish.py`, which may raise the versionCode), then build the Play bundle (`./gradlew :app:bundlePlayRelease`) so it gets the same versionCode, and upload it.
