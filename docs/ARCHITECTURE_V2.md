# RenderMyMind v2 architecture

The v2 tracker is intentionally split into small components so OCR tuning cannot silently change capture lifecycle, duplicate suppression, or persistence.

## Data flow

`MediaProjection -> ProjectionFrameSource -> MlKitOcrEngine -> ArcaeaResultDetector -> ResultStateMachine`

When the state machine accepts a distinct result:

`ProjectionFrameSource (native frame) -> MlKitOcrEngine (multilingual) -> ArcaeaResultParser -> FileResultStore`

Debugging is a side channel only:

`Detector / Parser -> DebugBus -> TrackingDebugOverlay`

## Invariants

1. **Capture never interprets pixels.** `ProjectionFrameSource` only owns MediaProjection surfaces and frame acquisition.
2. **OCR never owns lifecycle.** `MlKitOcrEngine` returns recognised lines and geometry. It cannot arm, re-arm, capture, save, or suppress anything.
3. **Detection is evidence, not state.** `ArcaeaResultDetector` answers whether the current frame looks like a result and supplies evidence strength.
4. **OCR text is never an exact primary key.** `ResultIdentityMatcher` combines score/title evidence with a perceptual frame fingerprint and can return SAME, DIFFERENT, or UNKNOWN.
5. **Only the state machine requests captures.** `ResultStateMachine` owns candidate confirmation, live-result hold, direct result switching, and real exit.
6. **UNKNOWN means wait.** Ambiguous identity evidence must not manufacture a new play.
7. **Parsing never deduplicates.** `ArcaeaResultParser` extracts fields from a native-resolution frame and reports partial data as null rather than inventing values.
8. **Debugging is read-only.** The overlay paints thin geometry only. It never feeds decisions back into capture/OCR/state and never paints recognised result text or scores over the game.
9. **Persistence receives one canonical object.** `PlayResult` is the boundary between recognition and saved history.
10. **Regression behaviour belongs in tests.** Bugs that teach us a lifecycle rule should become a test before further tuning.

## Current regression tests

- A one-digit OCR score wobble over effectively identical pixels does not create a duplicate result.
- A direct result-to-result switch can be accepted without requiring a menu or blank frame in between.
- The same result may be accepted again after a real exit from the result screen.

## Performance policy

The persistent watcher uses a low-resolution frame and Latin OCR. Expensive multilingual OCR runs only after the state machine accepts a distinct result and requests a native-resolution capture.

Probe cadence is owned by lifecycle state: idle may be slower, while candidate/live states may be sampled faster. This keeps continuous gameplay work bounded without making result transitions inert.
