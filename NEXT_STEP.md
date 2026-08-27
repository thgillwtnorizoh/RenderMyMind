# Next step: first actual game adapter

Do not add general OCR yet.

For one rhythm game only:

1. Gather 20-50 result-screen screenshots across songs/difficulties/resolutions.
2. Define normalized ROIs (0.0-1.0 coordinates) for 2-4 distinctive UI elements.
3. Add a cheap `GameResultScreenDetector` that confirms those regions.
4. Measure false positives through a normal play session.
5. When false positives are low, add a high-resolution recognition burst.
6. Add digit-only score/judgement recognition before general song-title OCR.
7. Store raw recognised fields + confidence separately from calculated rating.

The capture service should remain game-agnostic. Game-specific logic belongs behind an
adapter interface so more rhythm games do not turn the service into a switch-statement swamp.
