# 2.0 / version code 13

## Product direction

Turn the piano into a small creation and practice tool. Recording, replay, MIDI export, and a metronome give free users reasons to return. Pro remains a lifetime upgrade for additional sounds, adjustable note volume, and an ad-free workspace. No price or product-ID change is included. Conversion improvement is a hypothesis to evaluate after release, not an established outcome.

## Fixed

- Shared MIDI buffers could be two bytes long after an instrument change, causing a crash on note-off. Every message now uses the correct independent buffer.
- Touches could propagate to scrolling and cancel notes; individual pointers now own their notes, sliding releases the old pitch, and cancellation clears held notes.
- Changing octave while holding a key could release a different pitch. The original pitch is released before changing the range.
- Pause, instrument changes, and playback completion now clear notes and sustain to prevent stuck audio.
- Selected instruments are restored after the synthesizer starts and retained when Play refreshes the entitlement.
- Removed an automatic PiP attempt although the manifest explicitly disabled PiP.
- Removed the invented USD fallback price. Checkout uses the eligible Play offer token and its localized price.
- Restore reports success only after Play returns a completed purchase; empty and failed restores have explicit feedback.
- Already-owned responses trigger a purchase query instead of granting access from the response alone. Pending purchases do not unlock Pro.
- Billing reconnects, refreshes on resume, reports launch errors, dispatches callbacks on the main thread, cancels delayed work on destruction, and retries acknowledgments.
- Failed Play queries retain offline access; successful queries reconcile entitlement. A stale query cannot overwrite a newly completed purchase.
- Ads require UMP `canRequestAds()`, with the privacy-options entry point displayed when required. Consent callbacks and ad initialization recheck Pro entitlement.
- Banners occupy their own layout space instead of covering keys. System navigation and cutout insets are included.
- Replaced the corrupt bundled audio AAR with the official release of the same version. Removed the obsolete compressed-library workaround.

## Vendored audio integrity

Source: https://github.com/billthefarmer/mididriver/releases/download/v1.29/MidiDriver-1.29.aar

SHA-256: `ED8A8F3744CFFC2937AD1AB88323F8FCEB40F2CFBFF9F0E158390E8060604551`

All six 64-bit native libraries (`libmidi`, `liboboe`, `libc++_shared` on arm64-v8a and x86_64) have 16384-byte ELF LOAD alignment. Packaging uses uncompressed libraries with AGP 9.4.1. Validate the final APK with SDK `zipalign -c -P 16 -v 4` and use a 16KB-page device for final native testing.

## Required before publishing

1. Install a release-signed build through Play internal testing. Confirm the existing non-consumable `midi_piano_pro` product and purchase option are active and available in the tester's country. Check that displayed currency/price matches Play checkout.
2. Use a license tester to verify purchase, cancellation, pending payment completion, restore after reinstall, account without a purchase, interrupted connectivity, and acknowledgement. Confirm all 24 sounds and ad removal after purchase.
3. Test consent-required and consent-not-required regions with UMP test geography, change privacy choices, verify no duplicate banners, and confirm Pro remains ad-free. Debug builds use Google's test banner ID.
4. On real hardware, listen for low latency, play chords and slides, switch instruments/octaves while holding notes, rotate, background/resume, and test Bluetooth/headphone routing. Test both regular and 16KB Android devices.
5. Record with sustain and sound changes; replay; export; import the MIDI into a DAW; reopen the app and replay the saved take. Verify replacement confirmation and the five-minute limit.
6. Review phone/tablet, landscape, large font, TalkBack, gesture/three-button navigation, and light/dark appearance. Sign with the existing release identity and review Play pre-launch reports.

Play configuration, production signing, real payment verification, and server-side purchase validation are not provided by this local repository. Purchase processing remains client-side; a secure backend is the appropriate future addition for stronger fraud resistance and purchase monitoring. Recording is MIDI events, not an audio/WAV recording. Only the latest take is stored in the app; export takes to retain multiple recordings.
