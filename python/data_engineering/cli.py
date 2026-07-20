"""Command-line interface for repeatable dataset operations."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path
from typing import Sequence

from .etl.legacy_import import deduplicate_records, import_legacy_csv
from .io import read_jsonl, read_records, write_jsonl, write_records
from .models import SplitRatios
from .splitters.group_stratified import group_stratified_split
from .validators.manifest import validate_records
from .versioning.manifest import build_manifest
from vision_ai.models import VisionPrediction
from vision_ai.validators import validate_predictions


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="apartment-data")
    commands = parser.add_subparsers(dest="command", required=True)

    legacy = commands.add_parser("import-legacy", help="convert a legacy CSV to JSONL")
    legacy.add_argument("input", type=Path)
    legacy.add_argument("output", type=Path)
    legacy.add_argument("--skip-invalid", action="store_true")

    validate = commands.add_parser("validate", help="validate a JSONL record manifest")
    validate.add_argument("input", type=Path)
    validate.add_argument("--root", type=Path)
    validate.add_argument("--require-files", action="store_true")

    split = commands.add_parser("split", help="create leakage-safe dataset splits")
    split.add_argument("input", type=Path)
    split.add_argument("output", type=Path)
    split.add_argument("--train", type=float, default=0.8)
    split.add_argument("--validation", type=float, default=0.1)
    split.add_argument("--test", type=float, default=0.1)
    split.add_argument("--seed", type=int, default=42)

    version = commands.add_parser("manifest", help="create a version manifest")
    version.add_argument("input", type=Path)
    version.add_argument("output", type=Path)
    version.add_argument("--version", required=True)

    vision = commands.add_parser(
        "vision-validate", help="validate serialized Vision AI predictions"
    )
    vision.add_argument("input", type=Path)
    vision.add_argument("--records", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.command == "import-legacy":
        records = deduplicate_records(
            import_legacy_csv(args.input, strict=not args.skip_invalid)
        )
        write_records(args.output, records)
        return 0
    if args.command == "validate":
        issues = validate_records(
            read_records(args.input), root=args.root, require_files=args.require_files
        )
        for issue in issues:
            print(json.dumps(asdict(issue), ensure_ascii=False))
        return 1 if issues else 0
    if args.command == "split":
        ratios = SplitRatios(args.train, args.validation, args.test)
        splits = group_stratified_split(read_records(args.input), ratios, seed=args.seed)
        records = [record for name in ("train", "validation", "test") for record in splits[name]]
        write_records(args.output, records)
        return 0
    if args.command == "manifest":
        manifest = build_manifest(read_records(args.input), version=args.version)
        write_jsonl(args.output, [manifest])
        return 0
    if args.command == "vision-validate":
        predictions = [
            VisionPrediction.from_dict(value) for value in read_jsonl(args.input)
        ]
        expected_ids = (
            {record.image_id for record in read_records(args.records)}
            if args.records is not None
            else None
        )
        issues = validate_predictions(predictions, expected_image_ids=expected_ids)
        for issue in issues:
            print(json.dumps(asdict(issue), ensure_ascii=False))
        return 1 if issues else 0
    raise AssertionError("unreachable")


if __name__ == "__main__":
    raise SystemExit(main())
