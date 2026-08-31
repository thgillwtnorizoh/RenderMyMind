# RenderMyMind Alpha

Android Arcaea result-screen tracking prototype with an offline screenshot inspector and a database-backed chart resolver.

Current alpha: **0.5.0-alpha**.

## Current architecture

The project intentionally keeps capture, OCR, detection, parsing, chart identity, persistence, and debug rendering separate.

```text
MediaProjection / imported screenshot
        ↓
ML Kit OCR
        ↓
Arcaea result detector
        ↓
full-resolution result parser
        ↓
semantic chart resolver
        ↓
result storage / inspector output
```

The offline **Inspect result images** mode is the primary calibration bench for the current phase. It runs the same native OCR and parser as live tracking without MediaProjection timing/state noise.

## Native OCR

Authoritative result parsing operates on the original captured image resolution. Preview images may be scaled for display, but the OCR input is not downscaled.

Bundled recognizers:

- Latin
- Chinese
- Japanese
- Korean

## Arcaea database schema v2

The database layer was rewritten for 0.5.0-alpha.

Semantic chart names remain the stable keys:

```text
PST / PRS / FTR / BYD / ETR / INS
```

Raw songlist classification is stored inside each chart instead of replacing those semantic keys with numbers.

The important special case is:

```text
BYD -> ratingClass 3, bydType 0
INS -> ratingClass 3, ratingClassAlias 1, bydType 1
```

This preserves the fact that Beyond and Inscribed share the same base `ratingClass` while remaining different semantic chart types.

See [`docs/ARCAEA_DATABASE_V2.md`](docs/ARCAEA_DATABASE_V2.md) for the schema and migration rules.

### Database tool

Migrate the old merged database:

```bash
python tools/arcaea_db.py migrate cheeseburger-merged.json arcaea-database-v2.json
```

Preferred migration with official songlist source classification:

```bash
python tools/arcaea_db.py migrate \
  cheeseburger-merged.json \
  arcaea-database-v2.json \
  --songlist songlist.json
```

Validate or inspect stats:

```bash
python tools/arcaea_db.py validate arcaea-database-v2.json
python tools/arcaea_db.py stats arcaea-database-v2.json
```

The Android app still accepts the previous `arcaea_wiki_entries` format during migration and derives classification from existing semantic chart keys. New imports are stored as `arcaea-database.json`; an existing private `cheeseburger-merged.json` remains readable until replaced.

## Hidden result-screen chart markers

`? / ???` is treated as an intentional result-screen state, not OCR failure.

Resolution is conservative:

1. use visible `PAST/PRESENT/FUTURE/BEYOND/ETERNAL/INSCRIBED` when present;
2. for a hidden marker, prefer a single database chart explicitly marked hidden-until-unlock;
3. otherwise a single Inscribed classification (`ratingClass=3`, `bydType=1`) may resolve the hidden chart;
4. otherwise leave the chart unresolved rather than guessing.

The screenshot inspector displays the resolution basis and the underlying database classification so these decisions are visible during testing.

## Build baseline

- Android Gradle Plugin: 9.3.0
- Gradle: 9.5.0
- JDK: 17+
- compileSdk / targetSdk: 36
- minSdk: 29
- package/application ID: `com.example.rhythmtracker`
- visible app name: **RenderMyMind Alpha**

CI deliberately uses Android API 36. A previous API 37 hosted-SDK attempt failed because the platform package was unavailable.

CI runs both database-tool Python tests and Android/Kotlin unit tests before building APKs.

## Testing focus

For the current phase, prefer **Inspect result images** over live MediaProjection testing. Useful test coverage includes:

- different device resolutions and aspect ratios
- normal difficulty labels and levels
- hidden `? / ???` charts
- Inscribed result screens
- long/multilingual song titles
- `TRACK COMPLETE`, `TRACK LOST`, and other result states
- score and PURE/FAR/LOST extraction

Once offline parsing is stable, live tracking can be reconnected and evaluated separately for state/lifecycle behaviour.
