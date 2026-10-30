# Changelog

Each version's section is the release note shown on the phone (ota/publish.py reads it).

## 1.4.0
- Truly to the Harman target: PureEQ now downloads your model's lab measurement and works out the correction on the phone, the way AutoEq does, against the Harman target as measured on the same rig. In-ears used to land on AutoEq's own in-ear target, about 1 dB away in places.
- The target's bass comes with it, and your taste test's bass sits on top as before.
- PureEQ remembers the model you picked for each pair of headphones, so the next profile for them starts with it.
- Profiles that got their model with 1.3 keep their correction; to move one over, open Fine-tune and tap Update to the Harman correction. Models measured only by crinacle keep AutoEq's own correction.

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
