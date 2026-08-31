# Arcaea database schema v2

RenderMyMind keeps semantic difficulty names as chart keys while preserving the original Arcaea songlist classification inside each chart.

The key rule is that **Beyond and Inscribed are not represented by replacing chart names with numbers**. Both are internally `ratingClass = 3`, so the raw class alone is not a unique semantic identity.

## Canonical chart shape

```json
{
  "charts": {
    "FTR": {
      "level": "10",
      "constant": 10.3,
      "notes": 1405,
      "classification": {
        "ratingClass": 2,
        "ratingClassAlias": null,
        "bydType": null,
        "source": "songlist"
      }
    },
    "BYD": {
      "level": "10+",
      "classification": {
        "ratingClass": 3,
        "ratingClassAlias": null,
        "bydType": 0,
        "source": "songlist"
      }
    },
    "INS": {
      "level": "11",
      "classification": {
        "ratingClass": 3,
        "ratingClassAlias": 1,
        "bydType": 1,
        "source": "songlist"
      }
    }
  }
}
```

`bydType` is RenderMyMind's normalized discriminator:

- `0` = Beyond
- `1` = Inscribed

`ratingClassAlias` is retained separately because it is source songlist data. It must not be silently replaced by the normalized `bydType` field.

## Top-level format

Schema-v2 databases use:

```json
{
  "format": "arcaea_tracker_database",
  "schema_version": 2,
  "source_format": "arcaea_wiki_entries",
  "updated_at": "...",
  "entries": []
}
```

The Android parser still accepts legacy `arcaea_wiki_entries` databases during the transition. Legacy chart keys are converted to the same in-memory classification model, but a rebuilt v2 database is preferred because it can preserve exact songlist fields and visibility metadata.

## Result-screen resolution

The result image parser and database resolver deliberately have separate jobs.

Visible result markers are authoritative:

- `PAST` -> `PST`
- `PRESENT` -> `PRS`
- `FUTURE` -> `FTR`
- `BEYOND` -> `BYD`
- `ETERNAL` -> `ETR`
- `INSCRIBED` -> `INS`

When the screen intentionally shows `??? / ?`, the tracker does not treat that as OCR failure. Resolution order is:

1. resolve the song title;
2. if exactly one chart is explicitly marked hidden-until-unlock, use it;
3. otherwise, if exactly one chart is classified as Inscribed (`ratingClass=3`, `bydType=1`), use it;
4. otherwise leave the chart unresolved rather than guessing.

This is why retaining the source classification matters for DREAD AREA-style hidden result screens.

## Migration tool

The repository now contains a dependency-free Python tool:

```bash
python tools/arcaea_db.py migrate cheeseburger-merged.json arcaea-database-v2.json
```

For the preferred rebuild, provide an official songlist JSON as well:

```bash
python tools/arcaea_db.py migrate \
  cheeseburger-merged.json \
  arcaea-database-v2.json \
  --songlist songlist.json
```

The tool preserves unknown existing fields, adds v2 classification objects, carries recognized visibility metadata, and validates the result before writing it.

Other commands:

```bash
python tools/arcaea_db.py validate arcaea-database-v2.json
python tools/arcaea_db.py stats arcaea-database-v2.json
```

## Source-of-truth rule

Semantic names (`PST`, `PRS`, `FTR`, `BYD`, `ETR`, `INS`) are the stable interface used by the app, OCR parser, stats, and UI. Numeric songlist fields are retained as evidence about how Arcaea represents that semantic chart.

Do not make downstream app code repeatedly reinterpret raw `ratingClass` values. That conversion belongs in the database schema layer only.
