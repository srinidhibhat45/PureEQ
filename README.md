# PureEQ

Personal sound tuning for any Android phone (Android 9+), in the spirit of Samsung's **Adapt Sound** and OPPO/realme's **Golden Sound**:

1. **Hearing check.** Beeps play in your left and right ear and you tap *Yes* when you hear one. PureEQ finds how quiet each frequency can be in each ear before you stop hearing it.
2. **Taste test.** You hear music as **A** and **B** (equally loud) and pick the one you like. Bass, warmth, vocals and treble are tuned a step at a time.
3. **Your EQ.** Both become one EQ curve per ear. Apply it to **all audio** on the phone, or **export** it to Wavelet, Poweramp Equalizer, JamesDSP, Equalizer APO/Peace, or any graphic EQ (10-band, Samsung 9-band, Android 5-band, 15-band, 31-band).

Everything runs on the phone. Profiles and test results never leave it; the app only goes online to check for its own updates. PureEQ is not a medical device.

## Install

The first install is by hand: open `https://github.com/srinidhibhat45/pureeq-updates/releases/latest` on the phone (or copy the newest APK in `dist/`), download the APK and open it. Allow "install unknown apps" for the browser or file manager when asked. After that, PureEQ updates itself (see [Updates](#updates)). The APK is signed with this computer's debug key, which is fine for personal sideloading, but every update must be built on this computer (Android only installs an update signed with the same key).

## Using it

1. Connect the headphones the profile is for. The result belongs to *your ears plus these headphones*; make one profile per pair.
2. Turn off other sound effects first: Dolby Atmos, Samsung Adapt Sound/EQ, other EQ apps, the EQ in your earbuds' app. Noise cancelling **on** helps; transparency/ambient **off**.
3. **New profile → Start.** The app:
   - checks the room noise (optional, uses the mic for 4 s, nothing stored),
   - asks you to set the volume so a repeating beep is *soft but clear* (keep it there until the test ends; the test pauses if it changes),
   - checks left/right (catches swapped earbuds and Mono audio),
   - runs the hearing check (*Quick* about 4 min, *Standard* about 5 min, *Thorough* about 8 min),
   - runs the taste test on the built-in track or **your own song** (any audio file on the phone).
4. **Your sound.** Listen with EQ on/off (loudness-matched), adjust *Hearing correction* (50 % default), Bass/Warmth/Vocals/Treble and Balance, then **Save**. All of these sit in one **Fine-tuning** card (here and in *Fine-tune*). Its **Reset** button puts every slider back to what PureEQ suggested from your tests, with *Undo*, and the card names what you've changed. A slider you move shows *Suggested +4.5 dB* (or whatever PureEQ suggested) under it; tap that to put back just that slider. **Redo taste test** (in *Fine-tune*) runs only the A/B part again for a saved profile and keeps its hearing result, name, correction and balance. The outcome replaces the four taste settings and becomes what Reset returns to; backing out before it finishes changes nothing. Profiles saved by 1.0.0 didn't keep the taste test's result, so until you redo their taste test, Reset restores only correction and balance.
5. On the home screen, **Apply to all audio** turns on the system-wide EQ, or use **Export**.

### Exports

| Target | What you get | How to load it |
|---|---|---|
| Wavelet | `GraphicEQ.txt` on Wavelet's 127-point grid | Wavelet › AutoEq › Import › pick the file |
| Poweramp Equalizer | `ParametricEQ.txt` (AutoEq/Equalizer APO format) | Preset › long-press › Import, or open the file with Poweramp Equalizer |
| JamesDSP / RootlessJamesDSP | GraphicEQ string | Arbitrary response equalizer › Edit as string › paste |
| Equalizer APO / Peace (PC) | Per-ear config (`Channel: L` / `Channel: R`) | Include from `config.txt` or paste into Peace |
| Phone/app graphic EQs | Slider values (10-band, Samsung 9-band, 5-band, JamesDSP 15-band, 31-band) | Type them in |
| Neutron, UAPP, Poweramp parametric | Filter list (type, frequency, gain, Q) + preamp | Type them in |
| Another phone | `*.pureeq.json` | Settings › Import a PureEQ profile |

Most apps can't EQ each ear separately, so single-curve exports average both ears. If your ears differ, the Export screen tells you how far to move Android's balance slider (Settings › Accessibility › Audio adjustment).

### System-wide EQ

Uses Android's `DynamicsProcessing` effect with a separate curve for the left and right channel. It runs from a small foreground service, which is why you see a notification.

- **All audio** (default) attaches to the global output mix. It works on most phones, but it depends on the manufacturer.
- **Music apps** (Settings) attaches to players that announce their audio session: Spotify, YouTube Music, Samsung Music, and Poweramp with its MusicFX option on. Use it if "All audio" has no effect.
- PureEQ pauses its own EQ while it plays test beeps or previews. It rebuilds the effect on every headphone change, and can switch to the profile you made for the headphones that just connected.
- Don't run two EQs at once (Wavelet, Dolby, Samsung effects), or they stack. Apps that use Android's low-latency path (some games) bypass system effects.

## Updates

PureEQ updates itself over the air from a GitHub repository that holds only release APKs and `update.json` (no source code).

**Publishing** (from this computer):

```bash
python3 ota/publish.py
```

- The first run asks which repository to use (default `<your GitHub login>/pureeq-updates`) and creates it. It has to be **public**, because phones download without logging in. The choice is saved in `ota.properties`.
- Each run tests and builds the release APK with the update address baked in, attaches it and `update.json` to a GitHub release, then waits until the public address shows the new version.
- The versionCode in `version.properties` is raised automatically if that version is already published. The release notes shown on the phone come from that version's section of `CHANGELOG.md`, or from `--notes "…"`.
- `--dry-run` builds and writes `ota/out/` without publishing. It needs the GitHub CLI logged in (`brew install gh`, `gh auth login`); use the account meant to host the updates.

**On the phone:**

- **When it checks.** On every app open; every 30 minutes, but only while the phone charges on Wi-Fi; otherwise once a day, never on low battery.
- **Verification.** A newer build is downloaded and checked for size, SHA-256, package name, version and signing key before anything installs.
- **Installing.** On Android 12+, once *Install unknown apps* is allowed for PureEQ (a card on Home asks once), updates install by themselves while the app is closed. Installs never happen while a song is playing or while a new profile is half-done; the system EQ comes back by itself afterwards, and a notification says what changed. With PureEQ open, a card offers *Install now*. Android 9–11, or phones whose own installer insists (some Xiaomi/OPPO/realme builds), ask once per update instead.
- **Settings › Updates** shows the version, the last check, *Check now*, and a switch to get a notification instead of automatic installs.
- **Moving repositories.** Copies built for one repository keep checking it. Moving to another needs one manual install.

## Battery

Nothing in PureEQ polls while it's in the background.

- **System EQ.** The foreground service only reacts to events (headphones, profile changes, players opening sessions). The equalizer itself runs inside Android's audio mixer, only while sound plays; with nothing playing, the audio output sleeps as usual even with the EQ attached.
- **PureEQ's own audio thread.** It stops 3 s after its last sound and never runs in the background.
- **Leaving PureEQ.** A test in progress pauses with no timers left running, and the system EQ goes back onto other apps' audio until you return.
- **Update checks.** They follow the schedule above; network requests happen only then.
- **Measured** (Android 14 emulator, EQ on, app in the background): 10 ms of CPU per minute, and none of the main-thread wakeups a paused hearing test used to cost.
- **Cost of the EQ itself.** Like any system-wide EQ (Wavelet, Poweramp Equalizer), it keeps music on the regular mixer path instead of the low-power offload path some phones use, so playback with the screen off costs somewhat more. Turn *Apply to all audio* off when you don't need it.

## How it works

- **Stimuli.** Each presentation is three pulsed beeps, 225 ms on and 225 ms off, with 25 ms raised-cosine ramps (no clicks), rendered sample-accurately as 32-bit float. By default the beeps warble ±5 % at 8 Hz, so a single narrow earbud resonance can't masquerade as hearing loss. Settings has an option for pure tones.
- **Procedure.** Each ear and frequency gets a modified Hughson-Westlake staircase (ASHA 2005): 10 dB down after a yes, 5 dB up after a no. Ears are interleaved at random, frequencies run in clinical order (1 kHz upward, then downward), a silent catch trial comes every 7–12 trials to measure guessing, and any band that disagrees with both neighbours by 9 dB or more is re-checked, with all its answers pooled. The threshold is a maximum-likelihood fit of a psychometric function to all answers, with the false-alarm rate as its floor. A staircase stops when that estimate is known to about ±3 dB. In simulation (`HearingSessionTest`) a realistic listener is measured to within about 2 dB on average.
- **Hearing → EQ.** Relative level = measured threshold minus the normal-hearing curve (ISO 226:2003 with ISO 389-7 audiometric points). This captures ear and headphones together, which is what an EQ can fix. The baseline is the 25th percentile of both ears. Gain is strength × (level − baseline), 50 % by default (the half-gain rule). The curve uses monotone interpolation and fades out beyond the tested range. Limits are +12 dB, +6 dB below 150 Hz and −6 dB. It is smoothed to 1/3 octave, then fitted with RBJ peaking/shelf filters, refining frequency, gain and Q together (the same approach as AutoEq).
- **Taste.** Bass is a low shelf at 105 Hz, Q 0.7 (the Harman bass filter). Warmth peaks at 250 Hz, vocals at 2 kHz, and treble is a high shelf at 6 kHz. Each round compares current−d against current+d, blind and loudness-matched with BS.1770 K-weighting on the actual song's spectrum. d halves each round. Bands are interleaved, and "Same" settles a band.
- **System EQ resolution.** The engine is FFT-based (2048 points at 40 ms frames) and applies a gain staircase with no interpolation. PureEQ gives every bin its own band below about 530 Hz and uses 1/16-octave bands above (107 bands). The last edge sits above Nyquist so every bin is set.

## Build from source

Requires JDK 17 and the Android SDK (platform 36). The version lives in `version.properties`; the update repository in `ota.properties` (written by `ota/publish.py`). A build without it can't update itself. `-Pota.manifestUrl=http://10.0.2.2:8765/update.json` points a debug build at a test server on this computer (debug builds allow plain http to 10.0.2.2 and localhost only).

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Debug builds log each test trial (`adb logcat -s PureEQ`); release builds don't.

## Layout

```
app/src/main/java/app/pureeq/
  dsp/       filters (RBJ biquads, responses), curves, parametric fitting, loudness, FFT,
             tone generator, the synthesised demo track
  hearing/   staircase, maximum-likelihood threshold, test session, ISO reference thresholds
  taste/     A/B taste session and tone controls
  profile/   profile model + JSON, hearing compensation, ProfileEq (all derived curves), exporters, store
  audio/     AudioTrack engine (A/B slots with crossfade), routing/volume/focus, noise meter, song decoder
  system/    DynamicsProcessing bands/effect, foreground service, boot receiver
  update/    over-the-air updates: update notice, download + verification, PackageInstaller, background job
  ui/        Compose screens, charts, view model
app/src/test/  unit tests: DSP accuracy, staircase simulation, profile maths and reset, taste convergence,
               export formats, update notice parsing
ota/publish.py  builds and publishes an update to GitHub Releases
```

## Limits worth knowing

- Results are relative: they shape the sound and don't diagnose hearing in dB HL. The test can't separate hearing from headphone response, and doesn't need to, since the EQ corrects both.
- Deep-bass thresholds are sensitive to earbud seal and room noise, which is why bass correction is capped at +6 dB. The taste test's bass round decides the rest.
- Some phones mix media at 16-bit with software volume. On those, beeps right at the edge of hearing in your most sensitive band (2–4 kHz) can't be reproduced perfectly, which can slightly under-state how good that band is.
- Bluetooth earbuds may add their own processing; turn it off in their app for the test.
