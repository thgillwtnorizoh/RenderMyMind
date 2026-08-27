# Next step: first actual game adapter

v0.2 now proves the generic low-cost architecture:

- persistent low-resolution projection surface
- throttled newest-frame pulls
- lightweight OCR sentinel
- native-resolution evidence capture only after an OCR hit
- duplicate suppression until the result screen disappears

The next work should make that sentinel game-specific rather than making the generic rules more complicated.

For one rhythm game only:

1. Gather 20-50 result-screen screenshots across songs, difficulties, orientations and resolutions.
2. Define normalized ROIs (0.0-1.0 coordinates) for 1-3 cheap result-screen anchors.
3. Replace the generic OCR ROI/keywords with a `GameAdapter` result gate.
4. Measure false positives through normal gameplay, menus, pause screens and song select.
5. Keep the native result PNG burst as evidence while tuning detection.
6. Once detection is reliable, add digit-only score/judgement recognition first.
7. Add song/chart resolution after numeric recognition is stable.
8. Store raw recognized fields + confidence separately from calculated rating/formula versions.

The capture service should remain game-agnostic. Game-specific logic belongs behind an adapter interface so more rhythm games do not turn the service into a switch-statement swamp.
