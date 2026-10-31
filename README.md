# PureEQ

Personal sound tuning for any Android phone (Android 9+), in the spirit of Samsung's **Adapt Sound** and OPPO/realme's **Golden Sound**:

1. **Listening check.** Beeps play in your left and right ear and you tap *Yes* when you hear one. PureEQ finds how quiet each frequency can be in each ear before you stop hearing it. A Bayesian test picks every beep to learn as much as possible, so it takes about 2 minutes. Missed a beep? Play it again once before answering.
2. **Taste test.** You hear music as **A** and **B** (equally loud) and pick the one you like. PureEQ starts from Harman's three groups of listeners and learns your bass and treble, usually in 5 to 7 rounds. A new pair is clearly marked, and you hear both before choosing.
3. **Your EQ.** Both become one EQ curve per ear, on top of your headphone model's measured correction from AutoEq if you pick one. Apply it to **all audio** on the phone, or **export** it to Wavelet, Poweramp Equalizer, JamesDSP, Equalizer APO/Peace, or any graphic EQ (10-band, Samsung 9-band, Android 5-band, 15-band, 31-band).

Everything runs on the phone. Profiles and results stay on it (and in your Google account's encrypted backup, if Android backup is on); the app only goes online to check for its own updates and, if you look up your headphone model, to download AutoEq's public list and that model's correction. PureEQ is not a medical device.

## Install

The first install is by hand: on the phone, open **[Download PureEQ (APK)](https://github.com/srinidhibhat45/pureeq-updates/releases/latest/download/PureEQ.apk)** (always the newest version; the public [pureeq-updates](https://github.com/srinidhibhat45/pureeq-updates) page has the same link and install steps), or copy the newest APK in `dist/`, then open the downloaded file. Allow "install unknown apps" for the browser or file manager when asked. After that, PureEQ updates itself (see [Updates](#updates)). Android only installs an update signed with the same key as the installed copy, or with a newer key whose rotation lineage includes it. The GitHub build is signed with this computer's debug key until a release key is set up ([key rotation](docs/key-rotation.md)), so keep that key and any release key backed up.

## Using it

1. Connect the headphones the profile is for. The result belongs to *your ears plus these headphones*; make one profile per pair.
2. Turn off other sound effects first: Dolby Atmos, Samsung Adapt Sound/EQ, other EQ apps, the EQ in your earbuds' app. Noise cancelling **on** helps; transparency/ambient **off**.
3. **New profile → Start.** The app:
   - asks whether the headphones are in-ear or over-ear (guessed from their name), and optionally which model they are: search AutoEq's database of over 6,000 measured headphones and PureEQ corrects that model from its lab measurement to the Harman target (it remembers the model per pair of headphones, so the next profile for them starts with it),
   - checks the room noise (optional, uses the mic for 3 s, nothing stored; it starts by itself once the mic is allowed),
   - asks you to set the volume so a repeating beep is *soft but clear* (keep it there until the test ends; the test pauses if it changes), then plays one beep on one side to check left/right (catches swapped earbuds and Mono audio),
   - runs the listening check, 7 bands per ear from 500 Hz to 8 kHz (*Quick* about 1½ min, *Standard* about 2 min, *Thorough* about 3 min),
   - runs the taste test on the built-in track or **your own song** (any audio file on the phone), usually 5 to 7 rounds.

   A whole new profile takes about 5 to 6 minutes.
4. **Your sound.** Listen with EQ on/off (loudness-matched), adjust *Ear tuning* (75 % default), Bass/Warmth/Vocals/Treble/Air, *Full sub-bass* (over-ear headphones, when it would cost volume) and Balance, then **Save**. All of these sit in one **Fine-tuning** card (here and in *Fine-tune*). Its **Reset** button puts every slider back to what PureEQ suggested from your tests, with *Undo*, and the card names what you've changed. A slider you move shows *Suggested +4.5 dB* (or whatever PureEQ suggested) under it; tap that to put back just that slider. **Redo taste test** (in *Fine-tune*) runs only the A/B part again for a saved profile and keeps its listening result, name, correction and balance. The outcome replaces bass and treble (warmth and vocals go back to 0) and becomes what Reset returns to; backing out before it finishes changes nothing. Profiles saved by 1.0.0 didn't keep the taste test's result, so until you redo their taste test, Reset restores only correction and balance.
5. On the home screen, **Apply to all audio** turns on the system-wide EQ for the output playing now, or use **Export**. PureEQ keeps on or off for each output, so the phone speaker stays untouched while your headphones get their profile. The ⋮ on any profile, the active one included, deletes it after a warning; deleting the active profile switches to your newest other one, and deleting the last one turns the system-wide EQ off.

### Exports

| Target | What you get | How to load it |
|---|---|---|
| Wavelet | `GraphicEQ.txt` on Wavelet's 127-point grid | Wavelet › AutoEq › Import › pick the file |
| Poweramp Equalizer | `ParametricEQ.txt` (AutoEq/Equalizer APO format) | Preset › long-press › Import, or open the file with Poweramp Equalizer |
| JamesDSP / RootlessJamesDSP | GraphicEQ string | Arbitrary response equalizer › Edit as string › paste |
| Equalizer APO / Peace (PC) | Per-ear config (`Channel: L` / `Channel: R`) | Include from `config.txt` or paste into Peace |
| Convolution (JamesDSP, ViPER4Android, Equalizer APO) | Per-ear minimum-phase impulse response: stereo 32-bit float WAV, 48 or 44.1 kHz, 8,192 taps, the exact curve of each ear at the system EQ's level | Load it in the app's convolver; pick the file that matches your music's sample rate |
| Phone/app graphic EQs | Slider values (10-band, Samsung 9-band, 5-band, JamesDSP 15-band, 31-band) | Type them in |
| Neutron, UAPP, Poweramp parametric | Filter list (type, frequency, gain, Q) + preamp | Type them in |
| Another phone | `*.pureeq.json` | Settings › Import a PureEQ profile |

Most apps can't EQ each ear separately, so single-curve exports average both ears. If your ears differ, the Export screen tells you how far to move Android's balance slider (Settings › Accessibility › Audio adjustment).

### System-wide EQ

Uses Android's `DynamicsProcessing` effect with a separate curve for the left and right channel. It runs from a small foreground service, which is why you see a notification.

- **All audio** (default) attaches to the global output mix. It works on most phones, but it depends on the manufacturer.
- **Music apps** (Settings) attaches to players that announce their audio session: Spotify, YouTube Music, Samsung Music, and Poweramp with its MusicFX option on. Use it if "All audio" has no effect.
- **One switch per output.** PureEQ keeps on or off for each output (the phone speaker, each pair of headphones by name, a car or Bluetooth speaker) with the profile it last played there (`profile/OutputStates`). Everything starts off, the phone speaker included, except headphones a profile was made with, which come on while PureEQ is on for another output. On an output it's off for, the service holds no effect at all and waits, and its notification says which outputs bring it back (**Turn off everywhere** stops it). The home switch, the tile, the tile menu and the notification's **Turn off** act on the output playing now; Settings › Where it's on lists every output with its own switch. The route is re-read when a device connects or disconnects and whenever playback starts or stops, which catches the media output picker. 1.5.2's single switch carried over as on for each pair a profile was made with.
- **Quick Settings tile.** Switches the system-wide EQ on and off from the swipe-down panel for the output playing now and shows the profile in use; with no profile yet it opens PureEQ. A long press opens a quick menu over whatever is on screen (it handles `QS_TILE_PREFERENCES`, so Android doesn't show App info): the EQ switch, the profiles to pick from with one tap (the headphones playing now are marked) and Fine-tune for the profile in use, which won't interrupt a listening check or taste test in progress. Settings › Quick Settings tile adds it (Android 13+ asks for you; on older versions, edit the panel and drag PureEQ in). Android 12+ won't let an app start its foreground service from the background, so turning it on opens an invisible activity that starts the EQ and closes at once, which folds the panel away. Turning it off leaves the panel open.
- PureEQ pauses its own EQ while it plays test beeps or previews. It rebuilds the effect on every output change, and can switch to the profile last used with the headphones that just connected, or the one made with them.
- Don't run two EQs at once (Wavelet, Dolby, Samsung effects), or they stack. Apps that use Android's low-latency path (some games) bypass system effects.

## Updates

PureEQ updates itself over the air from a GitHub repository that holds only release APKs and `update.json` (no source code).

**Publishing** (from this computer):

```bash
python3 ota/publish.py
```

- The first run asks which repository to use (default `<your GitHub login>/pureeq-updates`) and creates it. It has to be **public**, because phones download without logging in. The choice is saved in `ota.properties`.
- Each run tests and builds the release APK with the update address baked in, attaches it and `update.json` to a GitHub release, then waits until the public address shows the new version.
- Each release also carries the APK as `PureEQ.apk`, so `https://github.com/<repo>/releases/latest/download/PureEQ.apk` always downloads the newest version; the release page starts with that link, and the repository's README (written from `ota/download-readme.md` on every publish) gives it with install steps. `--links` does just this for the version already live, without building.
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

- **Stimuli.** Each presentation is two pulsed beeps, 225 ms on and 200 ms off, with 25 ms raised-cosine ramps (no clicks), rendered sample-accurately as 32-bit float. By default the beeps warble ±5 % at 8 Hz, so a single narrow earbud resonance can't masquerade as hearing loss. Settings has an option for pure tones. A beep follows a 350–600 ms gap; *No* unlocks once the last pulse has reached the ear (measured output latency, at least 250 ms on Bluetooth) plus 150 ms.
- **Procedure.** Bayesian active learning. One Gaussian belief covers the thresholds of all 7 bands (500 Hz–8 kHz) in both ears: calibration offset + reference + a smooth shape shared by both ears (squared exponential over octaves, 1-octave length scale, 4.5 dB spread up to 2 kHz widening to 8 dB at 8 kHz) ± a smooth left/right difference (4 dB, 1.2 octaves) + each ear's own level (3 dB) + a local part per band (3–4 dB) for narrow resonances. Answers follow a logistic psychometric function (2.5 dB spread) whose floor is the guessing rate from silent catch trials, with 3 % slips; each answer updates the belief by assumed-density filtering (exact quadrature on that band, then a rank-one update of all others). Bands are tested one at a time, octaves first (1, 2, 4, 8 kHz, 500 Hz, then 3 and 6 kHz, which are mostly predicted by then), ears interleaved. A band's first beep is clearly audible; each later beep goes to the level that minimises the expected posterior variance, never more than 10 dB above anything played so far. A band in one ear is done at a posterior SD of 3.5 dB (*Quick* 4.5, *Thorough* 2.75) with at least two beeps and one *yes*. Catch trials come every 8–12 beeps, every 12–18 once you've shown you don't guess. In simulation (`HearingSessionTest`, realistic listeners, Bluetooth timing) *Standard* takes 64 answers and about 1.8 minutes, with a mean error of 2.1 dB and 96 % within 5 dB; 1.1's staircase needed about 150 answers and 6.3 minutes for the same accuracy. Once a beep has played it can be played again, once; the answer then counts as heard on either of two listens (P = 1 − (1 − p)², about 2 dB easier at threshold), so a replay doesn't make a band read better than it is. Every answer is stored with the profile, so a result can be recomputed when the model improves (1.5 refits every saved result this way).
- **Reference.** A threshold minus the reference is how much less well a band is heard through these headphones than by a young normal-hearing listener on headphones of the same type tuned to the Harman target: ISO 389-7:2019 diffuse-field thresholds minus the difference between the Harman over-ear 2018 or in-ear 2019 target and diffuse field (AutoEq's target files). 1.1 used ISO 226 free-field thresholds, which imply a bass-light, brighter free-field target.
- **Listening check → EQ.** The EQ follows the smooth shape (the belief's mean at every frequency), with 500 Hz–1.5 kHz as the zero line: anchoring on 500 Hz–4 kHz, as 1.2–1.4 did, split a sloping loss around its own mean, cutting the low mids where hearing was fine and doubling the treble tilt. What both ears share is shrunk by a quarter of its own uncertainty and half the left/right difference by three quarters of its own, so what the answers can't tell from noise isn't corrected and a centred voice stays centred. Gain is strength (75 % by default) × deviation up to 6 dB, and 47 % of that beyond (0.35 dB per dB at 75 %): small, smooth deviations are mostly the headphones' own response; beyond that full gain sounds harsh, NAL-R gives 0.31 dB per dB and listeners fitting themselves choose about 0.29 (Sabin et al. 2020). Bands heard better than the reference are cut by the strength. It applies fully from 400 Hz to 8 kHz and fades to nothing at 200 Hz and 16 kHz. Boosts are capped at +2 dB in the bass, +8 dB from 500 Hz to 4 kHz and +6 dB from 8 kHz; cuts at −6 dB. Both ears share one set of broad peaking filters (Q 0.5–1.6, an octave apart). A real left/right difference adds a plain level difference below 1.5 kHz (a 20 Hz shelf, so no phase shift between the ears where they compare timing) and a small mirrored set of peaks above it (2.5 and 6 kHz, Q ≤ 1). In simulation (`FitSimulationTest`, the full check with simulated listeners), against 1.4: a normal-hearing listener's tonal colour 0.45 vs 0.42 dB RMS and left/right difference 0.14 vs 0.39 dB; a mild sloping loss (10 dB at 1 kHz to 35 dB at 4 kHz) gets an 8.0 dB tilt instead of 11.3 and no cut at 700 Hz; a flat 15 dB difference between the ears is read 3.3 dB short instead of 6.0; a one-sided treble loss 6–8 dB short instead of 9–10.
- **Headphone correction (optional).** Data from AutoEq (github.com/jaakkopasanen/AutoEq, MIT; measurements by oratory1990, Rtings, crinacle and others, with no stated licence, so they are fetched on the phone and never bundled). PureEQ downloads AutoEq's results index (about 850 KB, once, cached) from jsDelivr, with GitHub as the fallback that jsDelivr needs (it refuses files of this repository it hasn't cached, the repository being over its size limit), pinned to AutoEq commit `7ae0f56d` so a saved correction never changes.
  - **Computed from the measurement (1.4).** For a picked model, PureEQ fetches its result CSV (the measurement on AutoEq's 1.01-step grid) and the target for its rig without bass: Harman in-ear 2019 or over-ear 2018 as measured on that rig (711-type couplers, GRAS, HMS II.3 and crinacle's EARS + 711 each have their own version); the Brüel & Kjær 5128 and 4620 have no Harman target in AutoEq, so they use AutoEq's own (JM-1 with Harman filters, LMG 5128 0.6). The correction is AutoEq's `equalize()`, ported to Kotlin: error = measurement − target, both 0 dB at 1 kHz, minus its mean from 100 Hz to 10 kHz; Savitzky-Golay smoothing at 1/12 octave blending to 2 octaves between 6 and 8 kHz; slopes into a boost limited to 18 dB/octave except in dips lower than their neighbours; boosts capped at +6 dB; a final 1/5-octave smoothing. It reproduces AutoEq's published corrections within their 0.01 dB rounding. Above 4–8 kHz (AutoEq's own log-frequency blend) only half of the correction applies and boosts stop at +3 dB from 6 kHz: a fixture's treble says little about one person's ears, which differ by 5–30 dB there (Møller et al. 1995; Denk & Kollmeier 2020). The target's own bass shelf (105 Hz, Q 0.7: +9 dB in-ear, +6 dB over-ear, which rebuilds the full Harman curves within 0.1 dB) is added as an exact filter, so sealed in-ears get the full Harman bass; the bass guard (below) decides how much of it the whole EQ keeps. The rest of the curve is fitted with at most 12 parametric filters (shelves first, then peaks on the largest remaining feature below 10 kHz, broad above 4 kHz; about 0.1–0.3 dB RMS on real corrections). The profile stores the result's path, the target, the curve and the filters, so it works offline.
  - **AutoEq's published correction.** crinacle publishes no measurements, only finished corrections, so its models use AutoEq's `ParametricEQ.txt` (against AutoEq's recommended target for the rig, for in-ears its own in-ear target, within about 1 dB of Harman in-ear 2019 from 50 Hz to 12 kHz). So do profiles that got their model with 1.3, until the model is picked again (Fine-tune › Update to the Harman correction).
  - The correction's filters come first in every ear's chain, in the system EQ, the previews, the taste test and the exports, and count in the preamp. The listening check's reference curve is shifted by them (averaged over each beep's ±5 % warble), so its smooth deviation is only what the correction doesn't explain: the listener's ears and the fit. Names match on words, with model numbers and tier words (pro, max, lite, anc…) required to agree; trusted measurers are listed first. The last model picked for each output device (by name) is remembered and starts the next new profile for it.
- **Bass guard.** The bass of the whole EQ (correction with its target's bass, ear tuning and taste together) is kept within what it's worth, by low-shelf cuts shared by both ears and sized by direct search. Over-ear headphones keep bass below 50 Hz within 2 dB of the 50 Hz gain: at normal levels 20–40 Hz sits near threshold and Harman's over-ear preference model starts at 50 Hz, while a boost there costs driver excursion and as much volume (an HD 650 asks +10.5 dB at 20 Hz for the full target; guarded, its preamp is about −6.6 dB instead of −10). *Full sub-bass* in Fine-tuning lifts this and states its cost in volume on those headphones. Sealed in-ears keep their sub-bass. Nothing gets more than +12 dB (over-ear) or +15 dB (in-ear) below 200 Hz over 1 kHz. Taste-test pairs are guarded the same way.
- **Preamp.** Sized for music rather than for the curve's highest point. A mix's peaks are dominated by one band at a time, mostly the bass: at such a peak that band supplies its typical share (0.9 for bass, falling to 0.15 above 10 kHz) and the other bands the rest. The preamp covers the worst of these peaks with every band at its largest gain, never more than the largest boost, plus 0.2 dB. A +6 dB treble lift costs about 2 dB of level instead of 6, and bass plus treble boosts cost about the bass boost alone, so the EQ plays much closer to bypass loudness without clipping. GraphicEQ exports use the same level.
- **Taste.** Bass is a low shelf at 105 Hz, Q 0.7 (the Harman bass filter); treble is a broad high shelf from 2.5 kHz, Q 0.45, the shape Harman's listeners adjusted (1.0–1.4 used an 8 kHz shelf that no population data backs; profiles tuned then keep it until their taste test is redone). Air, a 10 kHz shelf, is manual. Each round plays two complete settings, blind and loudness-matched with BS.1770 K-weighting on the actual song's spectrum. A Bayesian model of your ideal setting (quadratic preference: 3 dB of bass or treble costs one unit; *Same* within ±0.25 units; 5–10 % slips) starts from Harman's three groups over bass and treble together (Olive, Welti & Khonsaripour 2019: 64 % like the target, 15 % want about 4.5 dB more bass, 21 % about 2.5 dB less bass with 1.5 dB more treble), widened by half when no measured correction brings the headphones to the target. Answers wait a second after each new pair and until both sides have been heard. Each next pair is the one expected to leave the least error, and the test stops after 4–8 rounds once the estimate settles. Simulated error is about 0.2 utility units (well under a dB) for a noisy listener, against 1.2–1.4 dB for 1.1's 12-round halving. Warmth (250 Hz), vocals (2 kHz) and air stay at 0 for manual fine-tuning.
- **Bass that follows the volume (1.6).** Ears lose bass faster than mids as sound gets quieter (ISO 226: about 0.31 dB at 100 Hz, 0.41 at 50 Hz and 0.07 at 10 kHz for each dB the level drops), so a balance chosen at one volume sounds thin below it. The taste test's media volume is stored with the profile (1.5 on) and is the reference: the system-wide EQ adds half of what the contours lose below it, a 105 Hz low shelf (Q 0.7) of 0.155 dB per dB up to +6 dB and a 10 kHz high shelf of 0.035 dB per dB up to +2 dB, and slightly less bass above it (down to −2 dB). Half, because full loudness compensation overshoots and only the change from the reference level counts (Holman & Kampmann 1978). The drop comes from Android's dB for each volume step (`getStreamVolumeDb`), else AOSP's default media curve, and applies only on the kind of output the profile was tuned on. The service follows Android's volume broadcast (debounced 150 ms) and rebuilds the effect in 0.25 dB steps. Where Android applies the volume before the effect, listening d dB down leaves d dB of headroom, more than the boost uses; on Bluetooth (absolute volume) and USB the mix can reach the effect at full scale, so the level drops by the boost there. Fine-tune has the switch (on by default) and shows what it adds at the current volume. Previews, the charts and exports leave it out.
- **System EQ resolution.** The engine is FFT-based (2048 points at 40 ms frames) and applies a gain staircase with no interpolation. PureEQ gives every bin its own band below about 530 Hz and uses 1/16-octave bands above (107 bands). The last edge sits above Nyquist so every bin is set. The DC bin, which also holds everything below about 12 Hz, is held at −12 dB rather than taking the 20 Hz boost. Latency is about one block (43 ms at 48 kHz). The effect's limiter follows each frame's RMS without look-ahead, so it is no clip guard; the preamp is.

## Build from source

Requires JDK 17 and the Android SDK (platform 36). The version lives in `version.properties`; the update repository in `ota.properties` (written by `ota/publish.py`). A build without it can't update itself. `-Pota.manifestUrl=http://10.0.2.2:8765/update.json` points a debug build at a test server on this computer (debug builds allow plain http to 10.0.2.2 and localhost only).

```bash
./gradlew :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
```

```bash
./gradlew :app:assembleGithubRelease
```

The APK lands in `app/build/outputs/apk/github/release/` (the Play build: `:app:bundlePlayRelease`). Debug builds log each test trial and what volume-linked bass adds (`adb logcat -s PureEQ`); release builds don't. To try output switching on an emulator, a debug build pretends media plays on another output: `adb shell am broadcast -a app.pureeq.debug.OUTPUT --es name "'Galaxy Buds2 Pro'" --ei type 8` (type: an `AudioDeviceInfo` type), and the same without `--es name` goes back to the real output. Release builds leave this out.

## Builds and distribution

Two flavours of the same app (`app.pureeq`): **github** updates itself from GitHub Releases as described above (`ota/publish.py` builds, tests and publishes it), and **play** is for Google Play, with no self-updater, install permissions or update checks, and no Updates section in Settings. Tasks name the flavour, for example `:app:testGithubDebugUnitTest`, `:app:testPlayDebugUnitTest`, `:app:assembleGithubRelease` (APK in `app/build/outputs/apk/github/release/`) and `:app:bundlePlayRelease`. Release builds are signed with the key in a git-ignored `keystore.properties` or the `PUREEQ_*` environment variables, and with this computer's debug key when neither is set.

- [Key rotation](docs/key-rotation.md): moving to a release key without breaking updates, and registering for Android developer verification.
- [Google Play checklist](docs/play-store.md): declarations, Data safety, store listing.
- [Privacy policy](docs/privacy-policy.md).

## Layout

```
app/src/main/java/app/pureeq/
  dsp/       filters (RBJ biquads, responses), curves, parametric fitting, loudness, FFT,
             tone generator, the synthesised demo track
  hearing/   Bayesian test session, threshold model (prior, belief updates), reference curves
  headphones/ AutoEq index parsing, name matching, downloads (pinned commit), the equalize() port and
             the correction computed from a model's measurement
  taste/     A/B taste session and tone controls
  profile/   profile model + JSON, hearing compensation, bass guard, ProfileEq (all derived curves), exporters, store
  audio/     AudioTrack engine (A/B slots with crossfade), routing/volume/focus, noise meter, song decoder
  system/    DynamicsProcessing bands/effect, foreground service, boot receiver
  update/    the Updater interface and update state; the GitHub updater itself (update notice, download +
             verification, PackageInstaller, background job) lives in app/src/github, a no-op in app/src/play
  ui/        Compose screens, charts, view model
app/src/test/  unit tests: DSP accuracy, hearing-test simulation, whole-pipeline fitting simulations, a clipping audit,
               profile maths, reset, taste convergence, export formats, update notice parsing, AutoEq index and
               matching, the equalize() port against AutoEq's published results (fixtures in
               src/test/resources/autoeq, test-only); app/src/testGithub: the update signer check
docs/          key rotation, the Google Play checklist, the privacy policy
ota/publish.py  builds, tests, checks the signing key of and publishes a GitHub-build update
```

## Languages and accessibility

- **Languages (1.6).** English, Spanish, German, French, Portuguese (Brazil) and Hindi, in `res/values*/strings*.xml`: one file per area (`strings_common`, `strings_home_settings`, `strings_setup_tests`, `strings_editor_export`). Names from the model (output names, modes, bands, check lengths, export layouts) stay English inside it, because output names are storage keys, and `ui/Labels.kt` shows them translated. Android 13+'s per-app language setting lists the six (`generateLocaleConfig`), and the APK keeps only those languages from the libraries (`localeFilters`). A new language: copy each English file into `values-xx`, translate, and check it with the placeholder and plural rules lint enforces. "Listening check" is never a medical hearing test in any language.
- **Accessibility.** Charts describe themselves to TalkBack (levels at 100 Hz, 1 kHz, 4 kHz and 10 kHz per ear), a row with a switch is one control (`Modifier.switchRow`), sliders announce name and value, choices have roles, and screen titles are headings. At large text, side-by-side buttons stack whenever a label wouldn't fit on one line (`AdaptiveButtons`), button heights are minimums and chart axis numbers stop growing at 1.3×. Checked at 200 % text.

## Limits worth knowing

- Results are relative: they shape the sound and don't diagnose hearing in dB HL. The test can't separate hearing from headphone response, and doesn't need to, since the EQ corrects both.
- Deep-bass thresholds are sensitive to earbud seal, room noise and the body noise you hear under sealed earphones. So the test starts at 500 Hz and the ear tuning adds at most +2 dB below 250 Hz; your bass comes from the headphones and the taste test.
- Thresholds above 8 kHz depend on how deep the earbuds sit, so the correction fades out above 8 kHz.
- A headphone correction is for the model's measured unit on a measurement rig. Individual units and fits differ, by several dB above about 6 kHz; the listening check's smooth fit absorbs some of that. The pinned AutoEq commit is from July 2025, so newer models aren't listed.
- Some phones mix media at 16-bit with software volume. On those, beeps right at the edge of hearing in your most sensitive band (2–4 kHz) can't be reproduced perfectly, which can slightly under-state how good that band is.
- Bluetooth earbuds may add their own processing; turn it off in their app for the test.
- Outputs are told apart by the name Android reports, so two pairs with the same name (or two unnamed wired pairs, both "Wired headphones") share one switch and one profile.
