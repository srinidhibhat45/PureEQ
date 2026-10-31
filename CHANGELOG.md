# Changelog

Each version's section is the release note shown on the phone (ota/publish.py reads it).

## 1.6.0
- Bass that follows the volume. Your profile remembers the volume you tuned it at; turn down from there and PureEQ adds back the bass your ears lose at lower levels (about 1.5 dB for every 10 dB quieter, up to +6 dB, plus a touch of treble), and eases off a little when you turn up. Fine-tune shows what it adds right now and has a switch. For profiles tuned with PureEQ 1.5 or later; redo the taste test of an older one to use it.
- PureEQ now speaks Spanish, German, French, Portuguese and Hindi, following your phone's language. On Android 13 and later you can also pick its language in Settings › Apps › PureEQ › Language.
- Better with TalkBack and large text: charts say what they show, every switch row is one control that reads its label, sliders speak their values, and buttons stack instead of breaking words.

## 1.5.3
- PureEQ now keeps on or off for each output: the phone speaker, each pair of headphones, a car or a Bluetooth speaker. Profiles are tuned for headphones, so the phone speaker now plays untouched instead of quieter and duller. Unplug your headphones and PureEQ steps aside; plug them back in and it comes back on by itself, with the profile you last used with them.
- Want it on the speaker too? Switch it on while the speaker plays, and that's kept for the speaker. The switch on the home screen, the tile and the notification's Turn off all act on the output playing now.
- Settings › Where it's on lists your outputs, each with its own switch, so you can turn PureEQ on for your headphones before they're connected.
- Coming from 1.5.2 with PureEQ on: it stays on for each pair of headphones you made a profile with, and off for the phone speaker.

## 1.5.2
- Long-press the PureEQ tile in the swipe-down panel for a quick menu instead of App info: switch the EQ on or off, pick which profile (which headphones) it uses with one tap, or jump straight to Fine-tune. The headphones playing now are marked.

## 1.5.1
- Export for convolution: your exact curve for each ear as an impulse response (a stereo WAV at 48 or 44.1 kHz) for the convolver in JamesDSP, ViPER4Android or Equalizer APO. Left and right, balance and level come out exactly as PureEQ applies them.

## 1.5.0
- A more natural fit: your listening check is now measured from your low mids, and larger differences get gentler correction, close to what people choose when they tune their own sound. A result that slopes toward the treble gets about a third less treble tilt, and the low mids are no longer turned down. Saved profiles refit automatically.
- Fairer between your ears: a real difference between your ears now comes through (a flat one is read 3 dB short instead of 6), while everyone else's voices stay dead centre.
- A better taste test: treble is now the broad shelf Harman's listeners tuned, and the test starts from Harman's three groups of listeners. A new Air control for the very top is in Fine-tuning. Profiles tuned before keep their sound until you redo the taste test.
- No more mixed-up rounds: each new pair is clearly marked and waits until you've heard both A and B. In the listening check you can play a beep again once.
- Bass within reason: over-ear headphones keep the deepest bass (below 50 Hz) near the 50 Hz level, which saves several dB of volume on bass-light models; Full sub-bass in Fine-tuning brings it back. Sealed earbuds now get the full Harman bass.
- Headphone corrections trust lab measurements less in the high treble, where everyone's ears differ, and nothing below 20 Hz is boosted any more.
- "Hearing check" is now the Listening check and "Hearing correction" Ear tuning: PureEQ tunes sound and isn't a medical test. Profiles remember the volume they were tuned at, for volume-aware features coming next.

## 1.4.0
- Truly to the Harman target: PureEQ now downloads your model's lab measurement and works out the correction on the phone, the way AutoEq does, against the Harman target as measured on the same rig. In-ears used to land on AutoEq's own in-ear target, about 1 dB away in places.
- The target's bass comes with it, never boosted by more than 6 dB (AutoEq's own limit, which keeps bass-light headphones loud and clean), and your taste test's bass sits on top as before.
- PureEQ remembers the model you picked for each pair of headphones, so the next profile for them starts with it.
- Profiles that got their model with 1.3 keep their correction; to move one over, open Fine-tune and tap Update to the Harman correction. Models measured only by crinacle keep AutoEq's own correction.
- A Quick Settings tile: switch PureEQ's system-wide EQ on and off from the swipe-down panel. The tile shows the profile in use.
- Add it from Settings › Quick Settings tile (Android 13+ adds it for you), or swipe down twice, tap the pencil and drag PureEQ in.

## 1.3.1
- You can now delete any profile, the active one too (the ⋮ on its card). PureEQ warns first, then switches to your newest other profile, or turns the system-wide EQ off if it was your only one.
- Coming from 1.1? Since then: a 2-minute hearing check, a cleaner fit against well-tuned headphones, a shorter taste test, a louder EQ, and your headphone model's measured correction from AutoEq. Redo the hearing check for the full benefit.

## 1.3.0
- Your headphones' measured response: pick your model from AutoEq's database of over 6,000 headphones (measured by oratory1990, crinacle, Rtings and others) and PureEQ brings it to the Harman target, including the narrow peaks and dips a hearing check can't see.
- The hearing check then only corrects what's left, your ears and how the headphones sit, so nothing is corrected twice.
- Works without a hearing check too, and for saved profiles: Fine-tune › Add your headphones' measured correction. A switch turns it off; Reset turns it back on.
- The database (about 850 KB) downloads once, only when you search. Corrections are saved with the profile and work offline.

## 1.2.0
- A new hearing check, built like Apple's AirPods hearing test: it picks every beep to learn as much as possible about both ears at once and stops as soon as it knows enough. About 2 minutes instead of 6, and at least as accurate.
- Cleaner sound: PureEQ now corrects the smooth trend of your result, not every wobble, and only what your answers clearly show. Both ears share one filter set, so voices stay centred.
- Measured against the right target: your result is compared with well-tuned headphones of your type (in-ear or over-ear), not an old free-field curve that pushed headphones thin and bright. Bass is left to your taste; treble boosts stop at +6 dB.
- Louder: the EQ no longer turns everything down by the size of its biggest boost. Treble boosts now cost almost no level, and bass plus treble cost about the bass boost alone.
- A smarter taste test: bass and treble only, starting from what most listeners prefer. Usually 5 to 7 rounds instead of 12.
- Room check fixed: on many phones it read about 18 dB too quiet, so noisy rooms passed as quiet.
- The left/right check is now one beep right after setting the volume. A whole new profile takes about 5 to 6 minutes instead of 13 to 15.
- Existing profiles get the new fitting automatically; redo the hearing check for the full benefit.

## 1.1.1
- Fine-tuning is now one card with a clear Reset button that puts every slider back to what PureEQ suggested from your tests, with Undo.
- A slider you move shows PureEQ's suggested value under it ("Suggested +4.5 dB"); tap it to put back just that slider.
- Profiles made with 1.0 explain why their taste sliders can't be reset and offer Redo taste test right there.

## 1.1.0
- Reset in Your sound and Fine-tune: puts hearing correction, taste and balance back to what the tests recommended, with Undo. A moved slider shows its recommended value.
- Redo taste test in Fine-tune: a fresh A/B test for a saved profile that keeps its hearing result. The outcome becomes the new taste and what Reset returns to, which also gives 1.0.0 profiles a reset point.
- Updates over the air: new versions download, get verified and install by themselves (Android 12+, once you allow it).
- Lighter on the battery: no background checks except while charging on Wi-Fi and once a day, and the system EQ goes back onto other apps' audio as soon as you leave PureEQ.

## 1.0.0
- First release.
