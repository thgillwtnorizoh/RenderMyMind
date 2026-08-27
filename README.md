# RenderMyMind Alpha v0.2

Android rhythm-game result tracking prototype focused on staying out of the game's way.

v0.2 replaces the old once-per-second Surface attach/detach pulse with a persistent low-resolution projection surface and a lightweight OCR sentinel.

## v0.2 pipeline

```text
MediaProjection session
    ↓
one VirtualDisplay for the entire session
    ↓
persistent 480 px long-edge ImageReader surface
    ↓
every ~900 ms: pull newest queued frame
    ↓
crop one small normalised OCR region
    ↓
bundled ML Kit Latin text recogniser
    ↓
result-like text?
    ├─ no  → sleep until next probe
    └─ yes → switch SAME VirtualDisplay to native resolution
              ↓
            discard first post-resize frame
              ↓
            capture second frame
              ↓
            save PNG evidence
              ↓
            resize SAME VirtualDisplay back to low-res probe
```

The expensive native-resolution transition happens only after the light OCR gate believes the result screen is present, when gameplay should already be over.

## Important limitation

The current OCR sentinel is intentionally generic. It looks at a central/top region and checks result-ish words such as `RESULT`, `COMPLETE`, `SCORE`, `RANK`, `COMBO`, `PERFECT`, `MISS`, and `ACCURACY`.

It is a plumbing/performance prototype, not a universal detector. The real implementation should give every game adapter its own normalised ROI, anchors, keywords and recognition rules.

A generic OCR hit captures evidence only. It does **not** create an automatic score record yet.

## Result-screen evidence

Native result frames are saved to private app storage:

```text
/data/user/0/com.example.rhythmtracker/files/result-captures/result_<timestamp>.png
```

For the debug build, copy the newest evidence directory with ADB/run-as or inspect the path shown in the app telemetry.

The existing manual test button still appends `results.jsonl` in `context.filesDir`.

## Why the capture architecture changed

v0.1 repeatedly attached and detached the ImageReader Surface for every detector sample. During real rhythm-game testing that design was suspected of contributing to fixed-interval frame hitches.

v0.2 keeps the low-resolution Surface attached. App code does not register a display-FPS image listener. Instead, it explicitly pulls the newest queued frame on the probe cadence, so we avoid both Surface churn and a Java/Kotlin callback storm at 60/90/120 Hz.

## OCR model

v0.2 uses the bundled Latin ML Kit text-recognition model:

```text
com.google.mlkit:text-recognition:16.0.1
```

Bundling makes the sentinel available immediately after installation rather than relying on a first-run model download during a test session.

## Build baseline

- Android Gradle Plugin: 9.3.0
- Gradle: 9.5.0
- JDK: 17+
- compileSdk / targetSdk: 36
- minSdk: 29
- package/application ID: `com.example.rhythmtracker` (unchanged for now)
- visible app name: **RenderMyMind Alpha**

CI intentionally targets Android API 36. Do not bump the GitHub Actions build to API 37 unless the hosted Android SDK channel actually provides it; an earlier API 37 CI attempt failed because that platform package was unavailable.

The repository contains `gradle/wrapper/gradle-wrapper.properties` but not the wrapper JAR. On Windows, `bootstrap-wrapper.cmd` can download Gradle 9.5.0 and generate the wrapper.

## Run

1. Install/open **RenderMyMind Alpha**.
2. Tap **Start tracking**.
3. Allow notification permission if prompted.
4. Allow Android screen capture.
5. Launch the rhythm game and play normally.
6. After the session, return to RenderMyMind and inspect:
   - frames pulled
   - OCR probes
   - OCR result hits
   - screens captured
   - last OCR text
   - last PNG path
7. Stop from the app or persistent notification.

Android 14+ still requires explicit MediaProjection consent for each capture session.

## v0.2 performance test

The most important comparison is v0.1-style behaviour versus this persistent-surface design:

- does the old fixed-interval hitch disappear or become smaller?
- any new hitch at the ~900 ms OCR cadence?
- touch latency/audio behaviour
- FPS/jank with tracking OFF vs ON
- temperature and battery delta
- orientation changes
- lock/unlock
- projection revocation by another recorder
- whether OCR probing stalls during a long session

A one-time hitch after the song ends, when native capture wakes, is much less concerning than a repeating hitch during gameplay.

## Key files

- `capture/CaptureService.kt` - persistent probe, OCR cadence, native result burst and MediaProjection lifecycle
- `capture/LightResultOcrGate.kt` - generic tiny-ROI OCR sentinel
- `data/ResultCaptureStore.kt` - native result PNG evidence
- `data/PlayResult.kt` - score-domain skeleton
- `data/FileResultStore.kt` - append-only `results.jsonl` placeholder
- `TrackerRuntime.kt` - live prototype telemetry

See `NEXT_STEP.md` for the first game-specific adapter plan.
