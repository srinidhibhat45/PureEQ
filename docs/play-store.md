# Google Play launch checklist

PureEQ goes to Play as the `play` flavour: the same app and package (`app.pureeq`) as the GitHub build, minus the self-updater. Positioning: a personal-sound app that tunes your headphones to your ears and taste, not a medical device. Nothing has been submitted yet.

## 0. Decide first: one signing key for both builds?

Android only lets one build replace the other when both are signed with the same key (or a lineage from it). Choose before the first upload; changing Play's app signing key later goes through Play's key upgrade.

- [ ] **A. Shared key.** In Play Console's app signing setup, choose to use your own app signing key and upload the release key from [key-rotation.md](key-rotation.md) (Play Console walks you through its PEPK export tool). Play-installed and GitHub-installed copies then replace each other like any update, as long as the versionCode doesn't go down (a GitHub copy still on the debug key first needs one release-key GitHub update). Google recommends a separate upload key: create one with keytool and sign the bundle with it by setting the `PUREEQ_*` variables for that build (they take precedence over `keystore.properties`).
- [ ] **B. Separate keys.** Let Google generate the app signing key (the default) and sign uploads with the release key. Simplest, but moving between the GitHub and Play builds then means uninstalling first (profiles come back only through Android's backup or a profile export).

## 1. Build the upload

- [ ] Configure the signing key first ([key-rotation.md](key-rotation.md)); Play refuses debug-signed bundles.
- [ ] Build and test:

  ```bash
  ./gradlew :app:testPlayDebugUnitTest :app:bundlePlayRelease
  ```

  Upload `app/build/outputs/bundle/playRelease/app-play-release.aab`.
- [ ] Check what the Play build contains (`./gradlew :app:assemblePlayRelease`, then `"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump permissions app/build/outputs/apk/play/release/app-play-release.apk`): MODIFY_AUDIO_SETTINGS, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, INTERNET, plus AndroidX's own DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No REQUEST_INSTALL_PACKAGES, UPDATE_PACKAGES_WITHOUT_USER_ACTION or ACCESS_NETWORK_STATE, no update receiver or job, no update checks, and Settings has no Updates section.
- [ ] Version: `versionCode` comes from `version.properties`, shared with the GitHub build. Upload each release to Play with the same versionCode as its GitHub twin, so phones can move between them without a downgrade.

## 2. Developer account and testing

- [ ] Identity verification of the Play developer account. The same account registers the GitHub build for developer verification ([key-rotation.md](key-rotation.md#7-register-apppureeq-for-developer-verification)).
- [ ] Personal developer accounts created after 13 November 2023 must run a closed test with at least 12 opted-in testers for 14 days before they can apply for production access.
- [ ] Before submitting: the in-app wording should match the positioning, since reviewers see the app and its screenshots. Today the Home, setup and test screens say "hearing check"; the About screen mentions Apple's AirPods hearing test, hearing-aid fitting rules and hearing loss, and its Privacy text says PureEQ goes online to check for its own updates, which the Play build never does (`BuildConfig.SELF_UPDATER` tells the builds apart).

## 3. App content declarations

### Foreground service: special use

- [ ] Declare `FOREGROUND_SERVICE_SPECIAL_USE` for `EqService` (the manifest subtype says "Keeps the user's personal equalizer applied to system audio output"). Description:

  > PureEQ applies the user's personal sound profile, an equalizer, to all audio the phone plays. When the user turns on "Apply to all audio" in the app or with PureEQ's Quick Settings tile, a foreground service attaches Android's DynamicsProcessing audio effect to the output mix (or, in "Music apps" mode, to the audio sessions music players open) and keeps it applied while the user listens in other apps. The effect is released as soon as the service stops. A persistent notification shows the active profile and has a Turn off button; the user can also turn it off in the app or from the tile. None of the predefined types fits: the service doesn't play, record or cast media, sync data or track location. It only keeps the system-wide effect alive.

  Impact if the system stopped or deferred it:

  > The equalizer would switch off shortly after the user left PureEQ, so music in other apps would play without their sound profile.

- [ ] Video (screen recording with sound, about a minute, link that anyone can open, such as an unlisted YouTube video):
  1. PureEQ open with a saved profile; tap **Apply to all audio** (allow notifications when asked).
  2. The PureEQ notification appears, naming the profile and the output.
  3. Open a music app, play a song, go to the home screen: PureEQ is not on screen, the notification stays, and the song plays with the profile.
  4. Pull down Quick Settings and switch the PureEQ tile off and on: the sound changes back and forth.
  5. Tap **Turn off** in the notification: the notification disappears and the effect is gone.

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
>
> Optionally pick your headphone model from the open-source AutoEq database of over 6,000 measured headphones, and PureEQ corrects that model's measured response as well.
>
> Use your profile everywhere:
> • Apply it to all audio on the phone, or only to music apps.
> • Switch it on and off from a Quick Settings tile.
> • Profiles follow your headphones: connect a pair and its profile takes over.
> • Export it in AutoEq's formats for other equalizer apps on Android and on your computer.
>
> Private by design:
> • Everything runs on your phone. No account, no ads, no analytics.
> • The optional room check listens for 3 seconds to measure background noise; nothing is recorded or stored.
> • PureEQ only goes online to download headphone data from AutoEq when you look up your model.
>
> PureEQ is not a medical device and does not diagnose, treat, cure, or prevent any medical condition.

**Never in the listing, screenshots or release notes:** "hearing test", "hearing loss", "hearing aid", "audiogram", "dB HL", "screening", "clinically validated", and Apple or Samsung product names. Use "personal sound profile", "listening check" and "tunes your headphones to your ears and taste".

**Graphics:** icon 512 × 512, feature graphic 1024 × 500, at least two phone screenshots (home with a profile, the listening check, the taste test, the profile editor), checked against the list above.

## 5. Release

- [ ] Internal testing track first; read the pre-launch report.
- [ ] Closed test (see 2), then apply for production.
- [ ] Each later release: publish the GitHub build (`python3 ota/publish.py`, which may raise the versionCode), then build the Play bundle (`./gradlew :app:bundlePlayRelease`) so it gets the same versionCode, and upload it.
