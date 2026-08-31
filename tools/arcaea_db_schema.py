"""Arcaea tracker database schema-v2 helpers.

The semantic chart keys stay human-readable (PST/PRS/FTR/BYD/ETR/INS). Source songlist
classification is stored *inside* each chart so we do not lose the distinction between normal
Beyond and Inscribed, which both use ratingClass=3.

Normalized bydType:
    0 = Beyond
    1 = Inscribed
"""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Iterable

FORMAT_V1 = "arcaea_wiki_entries"
FORMAT_V2 = "arcaea_tracker_database"
SCHEMA_V2 = 2

BYD_TYPE_BEYOND = 0
BYD_TYPE_INSCRIBED = 1

SEMANTIC_TO_RATING_CLASS = {
    "PST": 0,
    "PRS": 1,
    "FTR": 2,
    "BYD": 3,
    "ETR": 4,
    "INS": 3,
}


@dataclass(frozen=True)
class Classification:
    rating_class: int
    rating_class_alias: int | None
    byd_type: int | None
    source: str

    @property
    def semantic(self) -> str | None:
        if self.rating_class == 0:
            return "PST"
        if self.rating_class == 1:
            return "PRS"
        if self.rating_class == 2:
            return "FTR"
        if self.rating_class == 4:
            return "ETR"
        if self.rating_class == 3:
            if self.byd_type == BYD_TYPE_BEYOND:
                return "BYD"
            if self.byd_type == BYD_TYPE_INSCRIBED:
                return "INS"
        return None

    def to_json(self) -> dict[str, Any]:
        return {
            "ratingClass": self.rating_class,
            "ratingClassAlias": self.rating_class_alias,
            "bydType": self.byd_type,
            "source": self.source,
        }


def normalize_semantic(raw: str) -> str | None:
    value = str(raw).strip().upper()
    aliases = {
        "PAST": "PST",
        "PST": "PST",
        "PRESENT": "PRS",
        "PRS": "PRS",
        "FUTURE": "FTR",
        "FTR": "FTR",
        "BEYOND": "BYD",
        "BYD": "BYD",
        "ETERNAL": "ETR",
        "ETR": "ETR",
        "INSCRIBED": "INS",
        "INS": "INS",
    }
    return aliases.get(value)


def classification_from_semantic(raw: str, source: str = "inferred-semantic") -> Classification:
    semantic = normalize_semantic(raw)
    if semantic is None:
        raise ValueError(f"Unknown semantic chart key: {raw!r}")
    if semantic == "BYD":
        return Classification(3, None, BYD_TYPE_BEYOND, source)
    if semantic == "INS":
        return Classification(3, 1, BYD_TYPE_INSCRIBED, source)
    return Classification(SEMANTIC_TO_RATING_CLASS[semantic], None, None, source)


def classification_from_songlist(
    rating_class: int,
    rating_class_alias: int | None,
    source: str = "songlist",
) -> Classification:
    rating_class = int(rating_class)
    alias = None if rating_class_alias is None else int(rating_class_alias)
    byd_type = None
    if rating_class == 3:
        byd_type = BYD_TYPE_INSCRIBED if alias == 1 else BYD_TYPE_BEYOND
    return Classification(rating_class, alias, byd_type, source)


def semantic_from_songlist(rating_class: int, rating_class_alias: int | None) -> str | None:
    return classification_from_songlist(rating_class, rating_class_alias).semantic


def migrate_database(root: dict[str, Any], songlist_root: Any | None = None) -> tuple[dict[str, Any], list[str]]:
    """Return a schema-v2 deep copy while preserving unknown source fields.

    If songlist_root is supplied, exact song IDs are used to replace inferred chart classification
    with source ratingClass/ratingClassAlias values and to carry visibility flags when available.
    """

    output = deepcopy(root)
    source_format = str(output.get("format") or "")
    if source_format not in {FORMAT_V1, FORMAT_V2}:
        raise ValueError(f"Unsupported input format: {source_format or '<missing>'}")

    warnings: list[str] = []
    songlist_index = build_songlist_index(songlist_root) if songlist_root is not None else {}

    entries = output.get("entries")
    if not isinstance(entries, list):
        raise ValueError("Database has no entries array")

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        song = entry.get("song")
        charts = entry.get("charts")
        if not isinstance(song, dict) or not isinstance(charts, dict):
            continue
        song_id = str(song.get("id") or "").strip()

        for raw_key, chart in charts.items():
            if not isinstance(chart, dict):
                continue
            semantic = normalize_semantic(raw_key)
            if semantic is None:
                warnings.append(f"{song_id or '<unknown>'}: unknown chart key {raw_key!r}")
                continue
            chart["classification"] = classification_from_semantic(
                semantic,
                source="legacy-v1-semantic" if source_format == FORMAT_V1 else "v2-fallback-semantic",
            ).to_json()
            _normalize_visibility(chart)

        official = songlist_index.get(song_id)
        if official is not None:
            _apply_songlist_classification(song_id, charts, official, warnings)

    output["format"] = FORMAT_V2
    output["schema_version"] = SCHEMA_V2
    output["source_format"] = source_format
    return output, warnings


def validate_database(root: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if root.get("format") != FORMAT_V2:
        errors.append(f"format must be {FORMAT_V2!r}")
    if root.get("schema_version") != SCHEMA_V2:
        errors.append(f"schema_version must be {SCHEMA_V2}")

    entries = root.get("entries")
    if not isinstance(entries, list):
        return errors + ["entries must be an array"]

    official_songs = 0
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            errors.append(f"entries[{index}] is not an object")
            continue
        song = entry.get("song")
        charts = entry.get("charts")
        if not isinstance(song, dict) or not isinstance(charts, dict):
            continue
        song_id = str(song.get("id") or "").strip()
        if song_id:
            official_songs += 1

        for raw_key, chart in charts.items():
            semantic = normalize_semantic(raw_key)
            if semantic is None or not isinstance(chart, dict):
                continue
            classification = parse_classification(chart.get("classification"))
            if classification is None:
                errors.append(f"{song_id}/{semantic}: missing classification")
                continue
            if classification.semantic != semantic:
                errors.append(
                    f"{song_id}/{semantic}: classification resolves to {classification.semantic!r}"
                )
            if semantic == "INS" and classification.rating_class_alias != 1:
                errors.append(f"{song_id}/INS: ratingClassAlias must be 1")

    if official_songs < 500:
        errors.append(f"official song count unexpectedly low: {official_songs}")
    return errors


def database_stats(root: dict[str, Any]) -> dict[str, int]:
    stats = {
        "songs": 0,
        "charts": 0,
        "explicit_classification": 0,
        "songlist_classification": 0,
        "inscribed": 0,
        "beyond": 0,
    }
    entries = root.get("entries")
    if not isinstance(entries, list):
        return stats

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        song = entry.get("song")
        charts = entry.get("charts")
        if not isinstance(song, dict) or not isinstance(charts, dict):
            continue
        if str(song.get("id") or "").strip():
            stats["songs"] += 1
        for raw_key, chart in charts.items():
            if normalize_semantic(raw_key) is None or not isinstance(chart, dict):
                continue
            stats["charts"] += 1
            classification = parse_classification(chart.get("classification"))
            if classification is None:
                continue
            stats["explicit_classification"] += 1
            if classification.source == "songlist":
                stats["songlist_classification"] += 1
            if classification.semantic == "INS":
                stats["inscribed"] += 1
            elif classification.semantic == "BYD":
                stats["beyond"] += 1
    return stats


def parse_classification(value: Any) -> Classification | None:
    if not isinstance(value, dict):
        return None
    try:
        rating_class = int(value["ratingClass"])
    except (KeyError, TypeError, ValueError):
        return None
    alias_value = value.get("ratingClassAlias")
    alias = None if alias_value is None else int(alias_value)
    byd_value = value.get("bydType")
    byd_type = None if byd_value is None else int(byd_value)
    if rating_class == 3 and byd_type is None:
        byd_type = BYD_TYPE_INSCRIBED if alias == 1 else BYD_TYPE_BEYOND
    return Classification(rating_class, alias, byd_type, str(value.get("source") or "database-v2"))


def build_songlist_index(root: Any) -> dict[str, list[dict[str, Any]]]:
    songs = _song_records(root)
    index: dict[str, list[dict[str, Any]]] = {}
    for song in songs:
        if not isinstance(song, dict):
            continue
        song_id = str(song.get("id") or "").strip()
        if not song_id:
            continue
        difficulties = song.get("difficulties")
        if not isinstance(difficulties, list):
            difficulties = song.get("charts")
        if not isinstance(difficulties, list):
            difficulties = []
        index[song_id] = [item for item in difficulties if isinstance(item, dict)]
    return index


def _song_records(root: Any) -> Iterable[dict[str, Any]]:
    if isinstance(root, list):
        return root
    if isinstance(root, dict):
        for key in ("songs", "songlist", "entries"):
            value = root.get(key)
            if isinstance(value, list):
                if key == "entries":
                    extracted = []
                    for item in value:
                        if isinstance(item, dict) and isinstance(item.get("song"), dict):
                            extracted.append(item["song"])
                        elif isinstance(item, dict):
                            extracted.append(item)
                    return extracted
                return value
    return []


def _apply_songlist_classification(
    song_id: str,
    charts: dict[str, Any],
    difficulties: list[dict[str, Any]],
    warnings: list[str],
) -> None:
    seen: set[str] = set()
    for source_chart in difficulties:
        if "ratingClass" not in source_chart:
            continue
        try:
            rating_class = int(source_chart["ratingClass"])
        except (TypeError, ValueError):
            continue
        alias_value = source_chart.get("ratingClassAlias")
        try:
            alias = None if alias_value is None else int(alias_value)
        except (TypeError, ValueError):
            alias = None
        classification = classification_from_songlist(rating_class, alias)
        semantic = classification.semantic
        if semantic is None:
            warnings.append(
                f"{song_id}: unsupported songlist ratingClass={rating_class} alias={alias!r}"
            )
            continue
        if semantic in seen:
            warnings.append(f"{song_id}: duplicate songlist semantic class {semantic}")
            continue
        seen.add(semantic)

        target = charts.get(semantic)
        if not isinstance(target, dict):
            warnings.append(f"{song_id}: songlist has {semantic} but merged DB does not")
            continue
        target["classification"] = classification.to_json()
        _copy_visibility(source_chart, target)


def _normalize_visibility(chart: dict[str, Any]) -> None:
    existing = chart.get("visibility")
    visibility = dict(existing) if isinstance(existing, dict) else {}
    for source_key, target_key in (
        ("hidden_until_unlocked", "hiddenUntilUnlocked"),
        ("hiddenUntilUnlocked", "hiddenUntilUnlocked"),
        ("hidden_until", "hiddenUntil"),
        ("hiddenUntil", "hiddenUntil"),
    ):
        if source_key in chart and target_key not in visibility:
            visibility[target_key] = chart[source_key]
    if visibility:
        chart["visibility"] = visibility


def _copy_visibility(source: dict[str, Any], target: dict[str, Any]) -> None:
    visibility = target.get("visibility")
    normalized = dict(visibility) if isinstance(visibility, dict) else {}
    for source_key, target_key in (
        ("hidden_until_unlocked", "hiddenUntilUnlocked"),
        ("hiddenUntilUnlocked", "hiddenUntilUnlocked"),
        ("hidden_until", "hiddenUntil"),
        ("hiddenUntil", "hiddenUntil"),
    ):
        if source_key in source:
            normalized[target_key] = source[source_key]
    if normalized:
        target["visibility"] = normalized
