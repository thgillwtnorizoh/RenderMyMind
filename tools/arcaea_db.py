#!/usr/bin/env python3
"""CLI for migrating, enriching and validating RenderMyMind's Arcaea database."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from arcaea_db_schema import database_stats, migrate_database, validate_database


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    temporary.replace(path)


def command_migrate(args: argparse.Namespace) -> int:
    source = load_json(args.input)
    songlist = load_json(args.songlist) if args.songlist else None
    migrated, warnings = migrate_database(source, songlist)
    errors = validate_database(migrated)

    if errors and not args.allow_invalid:
        print("Migration produced an invalid database:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 2

    write_json(args.output, migrated)
    stats = database_stats(migrated)
    print(f"Wrote {args.output}")
    print_stats(stats)
    if warnings:
        print(f"Warnings: {len(warnings)}")
        for warning in warnings[:50]:
            print(f"  - {warning}")
        if len(warnings) > 50:
            print(f"  ... {len(warnings) - 50} more")
    if errors:
        print(f"Validation errors kept because --allow-invalid was used: {len(errors)}")
    return 0


def command_validate(args: argparse.Namespace) -> int:
    root = load_json(args.database)
    errors = validate_database(root)
    stats = database_stats(root)
    print_stats(stats)
    if errors:
        print(f"Validation failed with {len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 2
    print("Validation OK")
    return 0


def command_stats(args: argparse.Namespace) -> int:
    print_stats(database_stats(load_json(args.database)))
    return 0


def print_stats(stats: dict[str, int]) -> None:
    print(
        "songs={songs} charts={charts} explicit_classification={explicit_classification} "
        "songlist_classification={songlist_classification} BYD={beyond} INS={inscribed}".format(**stats)
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="RenderMyMind Arcaea database schema-v2 utility"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    migrate = sub.add_parser(
        "migrate",
        help="convert a legacy merged database to schema v2 and optionally enrich from songlist",
    )
    migrate.add_argument("input", type=Path)
    migrate.add_argument("output", type=Path)
    migrate.add_argument(
        "--songlist",
        type=Path,
        help="official songlist JSON used to preserve ratingClass/ratingClassAlias and visibility",
    )
    migrate.add_argument(
        "--allow-invalid",
        action="store_true",
        help="write output even when validation fails (diagnostic use only)",
    )
    migrate.set_defaults(func=command_migrate)

    validate = sub.add_parser("validate", help="validate a schema-v2 database")
    validate.add_argument("database", type=Path)
    validate.set_defaults(func=command_validate)

    stats = sub.add_parser("stats", help="print schema-v2 classification statistics")
    stats.add_argument("database", type=Path)
    stats.set_defaults(func=command_stats)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
