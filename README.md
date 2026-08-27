# Rhythm Tracker Prototype v0.1

A deliberately small Android skeleton for testing the hard plumbing before OCR:

- explicit MediaProjection consent
- `mediaProjection` foreground service
- one VirtualDisplay per projection token
- pulse capture: attach ImageReader only long enough to sample one frame
- detector stream capped to a 640 px long edge
- resize the existing VirtualDisplay on orientation/captured-content changes
- cheap perceptual-hash stability gate
- **no automatic score save from the generic detector**
- append-only JSONL persistence test

## What this prototype proves

1. Can a rhythm game run normally while the tracker stays alive?
2. Does a one-frame-per-second detector survive long sessions?
3. Does projection remain correct across portrait → landscape and back?
4. Does Android stop/revoke projection cleanly?
5. Can we keep the expensive recogniser completely asleep until a candidate appears?

## Build baseline

- Android Studio: current stable recommended
- Android Gradle Plugin: 9.3.0
- Gradle: 9.5.0
- JDK: 17+
- compileSdk / targetSdk: 37
- minSdk: 29

This source bundle contains `gradle/wrapper/gradle-wrapper.properties`, but not the binary
`gradle-wrapper.jar`. The generation environment could not fetch binary wrapper files.

On Windows, run `bootstrap-wrapper.cmd` once. It downloads the official Gradle 9.5.0
distribution from `services.gradle.org` and generates the normal wrapper files.

If you already have Gradle 9.5.0 installed, you can instead run:

```bash
gradle wrapper --gradle-version 9.5.0
```

Then normal `./gradlew assembleDebug` / `gradlew.bat assembleDebug` works.

## Run

1. Install/open the app.
2. Tap **Start tracking**.
3. Allow notifications if prompted.
4. Allow Android's screen-capture prompt.
5. Launch the rhythm game and play normally.
6. Return to the tracker to inspect sampled-frame and stable-screen counters.
7. Stop from the app or the persistent notification.

Android 14+ is intentionally asked for full-display capture so you can grant permission
*before* launching the rhythm game. This is still a user-approved MediaProjection session.

## Important: the detector is not yet a result detector

`StabilityGateDetector` only answers: "has the visual content remained almost unchanged
across several detector samples?"

That is useful as the first gate because result screens are usually much more static than
gameplay, but pause menus, song-select screens, loading failures, etc. can also be stable.
The prototype therefore **never writes a score from this gate**.

The next real layer should be:

```text
stable candidate
    ↓
<GameName>ResultScreenDetector
    ↓ known ROIs / template features
confirmed result
    ↓
high-resolution 2–4 frame burst
    ↓
OCR / digit recognisers
    ↓
consensus + confidence
    ↓
local result record
```

## Key files

- `capture/CaptureService.kt` - projection lifecycle and pulse sampler
- `capture/ResultScreenDetector.kt` - cheap generic stability gate
- `data/PlayResult.kt` - result-domain skeleton
- `data/FileResultStore.kt` - tiny append-only persistence placeholder
- `TrackerRuntime.kt` - prototype telemetry visible in the activity

## First test I would run

Do one 20–30 minute real game session and watch for:

- game FPS/jank with tracking OFF vs ON
- device temperature
- battery delta
- whether detector sampling stalls
- orientation changes
- lock/unlock behaviour
- another screen recorder starting (Android should terminate this projection)

Only after that should OCR be added.
