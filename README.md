# MIDI Piano — Piano Studio 2.0

An Android pocket piano for playing, practicing, and capturing musical ideas. Java / Material 3, Android 7.0+.

## Studio

- A responsive keyboard: one octave on phones, two on wider screens; independent multi-touch fingers and sliding between notes.
- Record a take up to five minutes, replay it, and save a standard `.mid` file with Android's document picker. No microphone or storage permission needed.
- The latest completed take is saved locally and available after restarting. Starting another take asks before replacing it.
- A 40–240 BPM metronome with an accented first beat, sustain pedal, octave shifting, and optional note names.
- Sound, octave, sustain, note labels, tempo, and Pro note-volume settings persist.
- Free: six sounds and studio tools, supported by an adaptive banner. Pro: 24 sounds, adjustable note volume, and no ads. Preview Pro sounds before buying. Existing lifetime product: `midi_piano_pro`.

## Build

Use JDK 17 (also selected by `gradle/gradle-daemon-jvm.properties`). The current wrapper is Gradle 9.6.0 with Android Gradle Plugin 9.4.1, following the Android Studio migration. Install Android SDK 36 and configure your own untracked `local.properties`.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
./gradlew.bat :app:bundleRelease
```

Choose the equivalent JDK path on your machine. Sign the release bundle using the existing production signing identity before uploading.

The official MIDI Driver v1.29 AAR is vendored because its releases are not available through JitPack. See [release notes](RELEASE.md) for integrity and production checks.

## Verification

JVM tests parse exported files with `javax.sound.midi` to check note timing, tempo, program changes, pedal cleanup, and recording bounds. Android instrumentation covers keyboard sliding, cancellation, and octave changes. Real Play checkout and restore must be verified with a Play-installed internal-test build and a license tester; sideloaded debug builds cannot establish that the production product is configured correctly.
