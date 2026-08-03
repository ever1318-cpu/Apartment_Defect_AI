"""Command-line interface for repeatable dataset operations."""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from dataclasses import asdict, replace
from pathlib import Path
from typing import Sequence

from .etl.legacy_import import deduplicate_records, import_legacy_csv
from .database import (
    DatabaseConfig,
    DatabaseConfigurationError,
    DatabaseConnectionError,
    DatabaseQueryError,
    extract_defect_dataset_rows,
    inspect_database,
    test_database_connection,
)
from .io import read_jsonl, read_records, write_json, write_jsonl, write_records
from .models import SplitRatios
from .splitters.group_stratified import group_stratified_split
from .validators.manifest import validate_records
from .versioning.manifest import build_manifest
from vision_ai.backends import create_backend
from vision_ai.evaluation import EvaluationConfig, evaluate_predictions
from vision_ai.evaluation_models import GroundTruthAnnotation
from vision_ai.field_data import (
    build_dataset_version,
    check_image_quality,
    create_labeling_tasks,
    find_duplicates,
    ingest_images,
    validate_annotations,
)
from vision_ai.field_data_models import AnnotationRevision, IngestedImage
from vision_ai.inference import InferenceRunner
from vision_ai.models import VisionPrediction
from vision_ai.model_package import build_model_package, validate_model_package
from vision_ai.model_registry import ModelRegistry, STAGES
from vision_ai.release_readiness import run_release_check, write_release_artifacts
from vision_ai.pipeline import VisionPipeline
from vision_ai.training import TrainingRunner, load_training_backend
from vision_ai.training_dataset import build_training_dataset
from vision_ai.training_models import TrainingSpec, TrainingTasks
from vision_ai.validators import validate_predictions
from vision_ai.defect_dataset import defect_rows_to_dataset
from vision_ai.remote_images import materialize_remote_images, select_training_pilot
from vision_ai.training_report import write_comparison_report, write_training_report
from vision_ai.overlay_cleaning import clean_overlay_images
from vision_ai.colab_workflow import (
    export_colab_bundle,
    import_colab_result,
    prepare_colab_dataset,
)


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return parsed


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

    evaluate = commands.add_parser(
        "vision-evaluate", help="evaluate Vision predictions against ground truth"
    )
    evaluate.add_argument("ground_truth", type=Path)
    evaluate.add_argument("predictions", type=Path)
    evaluate.add_argument("output", type=Path)
    evaluate.add_argument("--iou-threshold", type=float, default=0.5)
    evaluate.add_argument("--confidence-threshold", type=float, default=0.25)
    evaluate.add_argument("--dataset-version")

    build_training = commands.add_parser(
        "vision-build-training-dataset",
        help="join split records and annotations into training inputs",
    )
    build_training.add_argument("records", type=Path)
    build_training.add_argument("annotations", type=Path)
    build_training.add_argument("output", type=Path)
    build_training.add_argument("--dataset-version", required=True)
    build_training.add_argument("--root", type=Path)
    build_training.add_argument(
        "--tasks",
        nargs="+",
        choices=("classification", "detection", "severity"),
        default=("classification", "detection", "severity"),
    )
    build_training.add_argument(
        "--classification-task",
        action="append",
        dest="classification_tasks",
    )

    defect_dataset = commands.add_parser(
        "vision-build-defect-dataset",
        help="convert exported defect/image rows into hierarchical AI records",
    )
    defect_dataset.add_argument("input", type=Path, help="UTF-8 JSONL defect rows")
    defect_dataset.add_argument("output", type=Path)
    defect_dataset.add_argument("--version", required=True)

    materialize = commands.add_parser(
        "vision-materialize-defect-images",
        help="download a deterministic training pilot from dataset image URLs",
    )
    materialize.add_argument("input", type=Path)
    materialize.add_argument("output", type=Path)
    materialize.add_argument("--max-per-split", type=_positive_int, default=100)
    materialize.add_argument(
        "--all", action="store_true", help="materialize every complete-label record"
    )
    materialize.add_argument("--workers", type=_positive_int, default=8)
    materialize.add_argument("--seed", type=int, default=42)

    training_report = commands.add_parser(
        "vision-training-report",
        help="create a standalone HTML utility report from a training run",
    )
    training_report.add_argument("run_directory", type=Path)
    training_report.add_argument("output", type=Path)

    comparison_report = commands.add_parser(
        "vision-training-comparison-report",
        help="compare original and cleaned/consistency training runs",
    )
    comparison_report.add_argument("original_run", type=Path)
    comparison_report.add_argument("cleaned_run", type=Path)
    comparison_report.add_argument("output", type=Path)

    clean_overlays = commands.add_parser(
        "vision-clean-overlays",
        help="create conservative overlay masks and cleaned image variants",
    )
    clean_overlays.add_argument("input", type=Path)
    clean_overlays.add_argument("output", type=Path)
    clean_overlays.add_argument("--workers", type=_positive_int, default=8)
    clean_overlays.add_argument("--review-coverage", type=float, default=0.25)

    train = commands.add_parser(
        "vision-train", help="execute a framework-neutral training workflow"
    )
    train.add_argument("spec", type=Path)
    train.add_argument("run_directory", type=Path)
    train.add_argument("--backend", default="reference")
    train.add_argument(
        "--device", choices=("auto", "cpu", "cuda"), default="auto"
    )
    train.add_argument(
        "--architecture",
        choices=("tiny_cnn", "convnext_tiny"),
    )
    train.add_argument(
        "--pretrained",
        action="store_true",
        help="use ImageNet pretrained weights when supported",
    )
    train.add_argument("--consistency-weight", type=float)
    train.add_argument("--epochs", type=_positive_int)
    train.add_argument("--batch-size", type=_positive_int)
    train.add_argument("--learning-rate", type=float)

    colab_export = commands.add_parser(
        "vision-colab-export",
        help="create a credential-free URL-backed Colab training bundle",
    )
    colab_export.add_argument("source_dataset", type=Path)
    colab_export.add_argument("training_spec", type=Path)
    colab_export.add_argument("output", type=Path)

    colab_prepare = commands.add_parser(
        "vision-colab-prepare",
        help="materialize a portable Colab bundle before GPU training",
    )
    colab_prepare.add_argument("bundle_directory", type=Path)
    colab_prepare.add_argument("output", type=Path)
    colab_prepare.add_argument("--workers", type=_positive_int, default=16)

    colab_import = commands.add_parser(
        "vision-colab-import",
        help="verify and import a completed Colab training result ZIP",
    )
    colab_import.add_argument("archive", type=Path)
    colab_import.add_argument("output", type=Path)

    export = commands.add_parser(
        "vision-export-onnx", help="export a PyTorch checkpoint to ONNX"
    )
    export.add_argument("run_directory", type=Path)
    export.add_argument("output", type=Path)
    export.add_argument("--checkpoint", default="best-model.pt")
    export.add_argument("--opset", type=int, default=17)
    export.add_argument(
        "--static-batch",
        action="store_false",
        dest="dynamic_batch",
        default=True,
    )

    package = commands.add_parser(
        "vision-package-model", help="build a deployable model package"
    )
    package.add_argument("training_run_directory", type=Path)
    package.add_argument("output_package_directory", type=Path)
    package.add_argument("--model-name", required=True)
    package.add_argument("--model-version", required=True)
    package.add_argument("--notes", default="")

    validate_package = commands.add_parser(
        "vision-validate-model-package", help="validate a model package"
    )
    validate_package.add_argument("package_directory", type=Path)
    validate_package.add_argument("--output", type=Path)
    validate_package.add_argument("--strict", action="store_true")

    inspect_package = commands.add_parser(
        "vision-inspect-model-package", help="inspect a model package manifest"
    )
    inspect_package.add_argument("package_directory", type=Path)

    register_model = commands.add_parser(
        "vision-register-model", help="copy a validated package into a registry"
    )
    register_model.add_argument("registry_directory", type=Path)
    register_model.add_argument("package_directory", type=Path)
    register_model.add_argument("--model-name", required=True)
    register_model.add_argument("--model-version", required=True)
    register_model.add_argument("--stage", choices=STAGES, default="development")
    register_model.add_argument("--notes", default="")

    promote_model = commands.add_parser(
        "vision-promote-model", help="change a registered model stage"
    )
    promote_model.add_argument("registry_directory", type=Path)
    promote_model.add_argument("model_name")
    promote_model.add_argument("model_version")
    promote_model.add_argument("--stage", choices=STAGES, required=True)
    promote_model.add_argument(
        "--previous-production-stage",
        choices=("staging", "archived"),
        default="staging",
    )

    list_models = commands.add_parser(
        "vision-list-models", help="list a local model registry"
    )
    list_models.add_argument("registry_directory", type=Path)
    list_models.add_argument("--model-name")

    serve = commands.add_parser("vision-serve", help="start optional FastAPI serving")
    serve.add_argument("--registry", type=Path, required=True)
    serve.add_argument("--model", required=True)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8000)
    serve.add_argument("--workers", type=int, default=1)

    release_check = commands.add_parser(
        "vision-release-check", help="validate registry and model release readiness"
    )
    release_check.add_argument("--registry", type=Path, required=True)
    release_check.add_argument("--model", required=True)
    release_check.add_argument("--version", required=True)
    release_check.add_argument("--deployment-profile", default="cpu")
    release_check.add_argument("--output", type=Path, default=Path("release-check"))
    release_check.add_argument("--strict", action="store_true")

    db_test = commands.add_parser(
        "vision-db-test", help="test a read-only PostgreSQL connection"
    )
    db_test.add_argument("--json", action="store_true")

    db_inspect = commands.add_parser(
        "vision-db-inspect", help="inspect PostgreSQL schemas and relations"
    )
    db_inspect.add_argument("--schema")
    db_inspect.add_argument("--table")
    db_inspect.add_argument("--json", action="store_true")
    db_inspect.add_argument(
        "--output",
        type=Path,
        default=Path("workspace/db-inspection/backupdb-schema.json"),
    )
    db_inspect.add_argument("--top-candidates", type=_positive_int, default=20)
    db_inspect.add_argument("--include-views", action="store_true")

    db_dataset = commands.add_parser(
        "vision-db-build-defect-dataset",
        help="build a leakage-safe hierarchical dataset from the read-only defect DB",
    )
    db_dataset.add_argument(
        "output",
        type=Path,
        nargs="?",
        default=Path("workspace/datasets/defect-db"),
    )
    db_dataset.add_argument("--version", required=True)
    db_dataset.add_argument("--limit", type=_positive_int)
    db_dataset.add_argument("--seed", type=int, default=42)
    db_dataset.add_argument("--train", type=float, default=0.70)
    db_dataset.add_argument("--validation", type=float, default=0.15)
    db_dataset.add_argument("--test", type=float, default=0.15)

    ingest = commands.add_parser(
        "vision-ingest-images", help="ingest field images into a content-addressed batch"
    )
    ingest.add_argument("source", type=Path)
    ingest.add_argument("output", type=Path)
    ingest.add_argument("--source-batch", required=True)
    ingest.add_argument("--operator", default="unknown")
    ingest.add_argument("--device-metadata", type=Path)

    quality = commands.add_parser(
        "vision-check-image-quality", help="inspect an ingestion batch for image quality"
    )
    quality.add_argument("ingestion_directory", type=Path)
    quality.add_argument("output", type=Path)
    quality.add_argument("--max-dimension", type=int, default=16_384)
    quality.add_argument("--min-dimension", type=int, default=64)
    quality.add_argument("--max-bytes", type=int, default=50 * 1024 * 1024)

    duplicates = commands.add_parser(
        "vision-find-duplicates", help="find exact and near-duplicate field images"
    )
    duplicates.add_argument("ingestion_directory", type=Path)
    duplicates.add_argument("output", type=Path)
    duplicates.add_argument("--similarity-threshold", type=float, default=0.92)

    tasks = commands.add_parser(
        "vision-create-labeling-tasks", help="create deterministic labeling work items"
    )
    tasks.add_argument("ingestion_directory", type=Path)
    tasks.add_argument("output", type=Path)
    tasks.add_argument(
        "--task-type",
        action="append",
        dest="task_types",
        required=True,
        choices=(
            "classification",
            "detection",
            "segmentation",
            "severity",
            "privacy_mask_review",
        ),
    )
    tasks.add_argument("--instructions-version", required=True)
    tasks.add_argument("--label-vocabulary-version", required=True)
    tasks.add_argument("--assignee")
    tasks.add_argument("--priority", type=int, default=0)

    annotation_qa = commands.add_parser(
        "vision-validate-annotations", help="validate annotation revisions and review state"
    )
    annotation_qa.add_argument("annotations", type=Path)
    annotation_qa.add_argument("output", type=Path)
    annotation_qa.add_argument("--label-vocabulary", type=Path)

    dataset_version = commands.add_parser(
        "vision-build-dataset-version", help="build an approved, leakage-safe dataset version"
    )
    dataset_version.add_argument("ingestion_directory", type=Path)
    dataset_version.add_argument("annotation_directory", type=Path)
    dataset_version.add_argument("output", type=Path)
    dataset_version.add_argument("--version", required=True)
    dataset_version.add_argument("--seed", type=int, default=42)
    dataset_version.add_argument("--privacy-mode", choices=("raw", "masked"), default="raw")

    predict = commands.add_parser(
        "vision-predict", help="run backend-neutral batch Vision inference"
    )
    predict.add_argument("input", type=Path, help="ImageRecord JSONL")
    predict.add_argument("output", type=Path, help="prediction JSONL")
    predict.add_argument(
        "--backend",
        default="reference",
        help="registered name (reference/onnx) or module:attribute",
    )
    predict.add_argument("--model", type=Path, help="model path for model backends")
    predict.add_argument("--model-version")
    predict.add_argument("--provider", action="append", dest="providers")
    predict.add_argument("--deployment-profile")
    predict.add_argument(
        "--root", type=Path, help="base directory for relative manifest image paths"
    )
    predict.add_argument("--errors", type=Path, help="optional inference error JSONL")
    predict.add_argument("--fail-fast", action="store_true")

    predict_image = commands.add_parser(
        "vision-predict-image", help="run Vision inference for one image file"
    )
    predict_image.add_argument("image", type=Path)
    predict_image.add_argument("output", type=Path, help="prediction JSONL")
    predict_image.add_argument(
        "--backend",
        default="reference",
        help="registered name (reference/onnx) or module:attribute",
    )
    predict_image.add_argument("--model", type=Path, help="model path for model backends")
    predict_image.add_argument("--model-version")
    predict_image.add_argument("--provider", action="append", dest="providers")
    predict_image.add_argument("--deployment-profile")
    predict_image.add_argument("--image-id")
    predict_image.add_argument("--fail-fast", action="store_true")
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
    if args.command == "vision-evaluate":
        ground_truth = [
            GroundTruthAnnotation.from_dict(value)
            for value in read_jsonl(args.ground_truth)
        ]
        predictions = [
            VisionPrediction.from_dict(value)
            for value in read_jsonl(args.predictions)
        ]
        report = evaluate_predictions(
            ground_truth,
            predictions,
            EvaluationConfig(
                confidence_threshold=args.confidence_threshold,
                iou_threshold=args.iou_threshold,
                dataset_version=args.dataset_version,
            ),
        )
        write_json(args.output, report.to_dict())
        for issue in (*report.errors, *report.warnings):
            print(json.dumps(asdict(issue), ensure_ascii=False, sort_keys=True))
        return 1 if report.errors else 0
    if args.command == "vision-build-training-dataset":
        selected = set(args.tasks)
        tasks = TrainingTasks(
            classification="classification" in selected,
            detection="detection" in selected,
            severity="severity" in selected,
            classification_tasks=tuple(
                args.classification_tasks or ("space", "trade", "component")
            ),
        )
        annotations = [
            GroundTruthAnnotation.from_dict(value)
            for value in read_jsonl(args.annotations)
        ]
        result = build_training_dataset(
            read_records(args.records),
            annotations,
            args.output,
            dataset_version=args.dataset_version,
            tasks=tasks,
            image_root=args.root if args.root is not None else args.records.parent,
        )
        print(result.training_spec_path)
        return 0
    if args.command == "vision-build-defect-dataset":
        if args.output.exists():
            raise FileExistsError(f"defect dataset directory already exists: {args.output}")
        items = defect_rows_to_dataset(
            read_jsonl(args.input), dataset_version=args.version
        )
        args.output.mkdir(parents=True)
        write_records(args.output / "records.jsonl", (item.record for item in items))
        write_jsonl(
            args.output / "annotations.jsonl",
            (item.annotation.to_dict() for item in items),
        )
        write_json(
            args.output / "dataset_manifest.json",
            {
                "dataset_version": args.version,
                "sample_count": len(items),
                "group_count": len({item.record.group_id for item in items}),
                "classification_tasks": [
                    "area",
                    "part",
                    "part_detail",
                    "work_kind",
                    "cause",
                ],
                "grouping_policy": "defect_id",
            },
        )
        print(args.output / "dataset_manifest.json")
        return 0
    if args.command == "vision-materialize-defect-images":
        records = read_records(args.input / "records.jsonl")
        annotations = {
            value["image_id"]: value
            for value in read_jsonl(args.input / "annotations.jsonl")
        }
        complete = [
            record for record in records
            if record.image_id in annotations
            and all(
                task in annotations[record.image_id].get("classifications", {})
                for task in ("area", "part", "part_detail", "work_kind", "cause")
            )
        ]
        if args.all:
            selected = tuple(complete)
        else:
            initial = select_training_pilot(
                complete, max_per_split=args.max_per_split, seed=args.seed
            )
            train_ids = {
                record.image_id for record in initial if record.split == "train"
            }
            known_labels: dict[str, set[str]] = {
                task: {
                    annotations[image_id]["classifications"][task]
                    for image_id in train_ids
                }
                for task in ("area", "part", "part_detail", "work_kind", "cause")
            }
            eligible = [
                record for record in complete
                if record.split == "train"
                or all(
                    annotations[record.image_id]["classifications"][task]
                    in known_labels[task]
                    for task in known_labels
                )
            ]
            selected = select_training_pilot(
                eligible, max_per_split=args.max_per_split, seed=args.seed
            )
        downloaded, failures = materialize_remote_images(
            selected, args.output, workers=args.workers
        )
        successful = {record.image_id for record in downloaded}
        write_records(args.output / "records.jsonl", downloaded)
        write_jsonl(
            args.output / "annotations.jsonl",
            (annotations[record.image_id] for record in downloaded),
        )
        write_jsonl(args.output / "download_failures.jsonl", failures)
        write_json(
            args.output / "materialization_manifest.json",
            {
                "selected_count": len(selected),
                "downloaded_count": len(downloaded),
                "failure_count": len(failures),
                "max_per_split": args.max_per_split,
                "all_records": args.all,
                "random_seed": args.seed,
                "split_counts": dict(Counter(record.split for record in downloaded)),
                "source_dataset": str(args.input),
                "complete_label_filter": True,
                "validation_labels_known_to_train": True,
                "successful_annotation_count": len(successful),
            },
        )
        print(args.output / "materialization_manifest.json")
        return 0
    if args.command == "vision-training-report":
        print(write_training_report(args.run_directory, args.output))
        return 0
    if args.command == "vision-training-comparison-report":
        print(
            write_comparison_report(
                args.original_run, args.cleaned_run, args.output
            )
        )
        return 0
    if args.command == "vision-clean-overlays":
        records = read_records(args.input / "records.jsonl")
        annotations = {
            value["image_id"]: value
            for value in read_jsonl(args.input / "annotations.jsonl")
        }
        cleaned, results = clean_overlay_images(
            records,
            args.output,
            workers=args.workers,
            review_coverage=args.review_coverage,
        )
        write_records(args.output / "records.jsonl", cleaned)
        write_jsonl(
            args.output / "annotations.jsonl",
            (annotations[record.image_id] for record in cleaned),
        )
        write_jsonl(
            args.output / "cleaning_results.jsonl",
            (asdict(result) for result in results),
        )
        write_json(
            args.output / "cleaning_manifest.json",
            {
                "input_count": len(records),
                "cleaned_count": len(cleaned),
                "error_count": sum(result.status == "error" for result in results),
                "review_count": sum(result.review_required for result in results),
                "review_coverage": args.review_coverage,
                "originals_preserved": True,
                "method": "conservative_color_mask_blur",
            },
        )
        print(args.output / "cleaning_manifest.json")
        return 0
    if args.command == "vision-train":
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(errors="replace")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(errors="replace")
        spec = TrainingSpec.from_dict(
            json.loads(args.spec.read_text(encoding="utf-8-sig"))
        )
        if (
            args.architecture is not None
            or args.pretrained
            or args.consistency_weight is not None
            or args.epochs is not None
            or args.batch_size is not None
            or args.learning_rate is not None
        ):
            spec = replace(
                spec,
                model_architecture=args.architecture or spec.model_architecture,
                pretrained=args.pretrained or spec.pretrained,
                consistency_weight=(
                    args.consistency_weight
                    if args.consistency_weight is not None
                    else spec.consistency_weight
                ),
                epochs=args.epochs or spec.epochs,
                batch_size=args.batch_size or spec.batch_size,
                learning_rate=(
                    args.learning_rate
                    if args.learning_rate is not None
                    else spec.learning_rate
                ),
            )
        backend_options = (
            {"device": args.device}
            if args.backend.strip().lower() == "pytorch"
            else {}
        )
        if args.device != "auto" and not backend_options:
            raise ValueError("--device is only supported by the pytorch backend")
        result = TrainingRunner(
            load_training_backend(args.backend, **backend_options)
        ).run(
            spec,
            args.run_directory,
            spec_directory=args.spec.parent,
        )
        print(result.manifest_path)
        return 0 if result.status == "completed" else 1
    if args.command == "vision-colab-export":
        project_root = Path(__file__).resolve().parents[2]
        print(
            export_colab_bundle(
                args.source_dataset,
                args.training_spec,
                args.output,
                project_root=project_root,
            )
        )
        return 0
    if args.command == "vision-colab-prepare":
        print(
            prepare_colab_dataset(
                args.bundle_directory, args.output, workers=args.workers
            )
        )
        return 0
    if args.command == "vision-colab-import":
        print(import_colab_result(args.archive, args.output))
        return 0
    if args.command == "vision-package-model":
        manifest = build_model_package(
            args.training_run_directory,
            args.output_package_directory,
            args.model_name,
            args.model_version,
            notes=args.notes,
        )
        print(manifest)
        return 0
    if args.command == "vision-validate-model-package":
        result = validate_model_package(
            args.package_directory, strict=args.strict
        )
        output = args.output or args.package_directory.with_name(
            f"{args.package_directory.name}-validation.json"
        )
        write_json(output, result.to_dict())
        print(json.dumps(result.to_dict(), ensure_ascii=False, sort_keys=True))
        print(output)
        return 0 if result.valid else 1
    if args.command == "vision-inspect-model-package":
        manifest = args.package_directory / "model_manifest.json"
        value = json.loads(manifest.read_text(encoding="utf-8-sig"))
        print(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True))
        print(manifest)
        return 0
    if args.command == "vision-register-model":
        registry = ModelRegistry(args.registry_directory)
        entry = registry.register(
            args.package_directory,
            args.model_name,
            args.model_version,
            stage=args.stage,
            notes=args.notes,
        )
        print(json.dumps(entry.to_dict(), ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "vision-promote-model":
        entry = ModelRegistry(args.registry_directory).promote(
            args.model_name,
            args.model_version,
            args.stage,
            previous_production_stage=args.previous_production_stage,
        )
        print(json.dumps(entry.to_dict(), ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "vision-list-models":
        registry = ModelRegistry(args.registry_directory)
        value = {
            "revision": registry.read().revision,
            "models": [
                item.to_dict() for item in registry.list(args.model_name)
            ],
        }
        print(json.dumps(value, ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "vision-serve":
        try:
            import uvicorn
        except ImportError as exc:
            raise RuntimeError(
                "Vision serving requires optional dependencies; "
                "install with `pip install -e \".[serving]\"`"
            ) from exc
        from vision_ai.serving import ServingConfig
        config = ServingConfig(
            registry_directory=str(args.registry),
            default_model_name=args.model,
            host=args.host,
            port=args.port,
            workers=args.workers,
        )
        os.environ.update(
            {
                "ADA_REGISTRY": config.registry_directory,
                "ADA_MODEL": config.default_model_name,
                "ADA_HOST": config.host,
                "ADA_PORT": str(config.port),
                "ADA_WORKERS": str(config.workers),
            }
        )
        uvicorn.run(
            "vision_ai.serving_entrypoint:create_app_from_env",
            factory=True,
            host=config.host,
            port=config.port,
            workers=config.workers,
        )
        return 0
    if args.command == "vision-release-check":
        report, manifest = run_release_check(
            args.registry,
            args.model,
            args.version,
            deployment_profile=args.deployment_profile,
        )
        report_path, manifest_path = write_release_artifacts(
            args.output, report, manifest
        )
        print(json.dumps(report.to_dict(), ensure_ascii=False, sort_keys=True))
        print(report_path)
        print(manifest_path)
        return 1 if report.status == "fail" or (
            args.strict and report.status == "warning"
        ) else 0
    if args.command == "vision-db-test":
        return _run_database_test(args)
    if args.command == "vision-db-inspect":
        return _run_database_inspection(args)
    if args.command == "vision-db-build-defect-dataset":
        return _run_db_defect_dataset(args)
    if args.command == "vision-ingest-images":
        metadata = (
            json.loads(args.device_metadata.read_text(encoding="utf-8-sig"))
            if args.device_metadata
            else {}
        )
        ingest_images(
            args.source,
            args.output,
            source_batch=args.source_batch,
            operator=args.operator,
            device_metadata=metadata,
        )
        manifest = json.loads(
            (args.output / "ingestion_manifest.json").read_text(encoding="utf-8")
        )
        print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
        return 1 if manifest["error_count"] else 0
    if args.command == "vision-check-image-quality":
        records = [
            IngestedImage.from_dict(value)
            for value in read_jsonl(args.ingestion_directory / "images.jsonl")
        ]
        results = check_image_quality(
            records,
            root=args.ingestion_directory,
            max_dimension=args.max_dimension,
            min_dimension=args.min_dimension,
            max_bytes=args.max_bytes,
        )
        write_jsonl(args.output, (item.to_dict() for item in results))
        summary = dict(sorted(Counter(item.status for item in results).items()))
        print(json.dumps(summary, sort_keys=True))
        return 1 if summary.get("fail", 0) else 0
    if args.command == "vision-find-duplicates":
        records = [
            IngestedImage.from_dict(value)
            for value in read_jsonl(args.ingestion_directory / "images.jsonl")
        ]
        groups = find_duplicates(
            records,
            root=args.ingestion_directory,
            similarity_threshold=args.similarity_threshold,
        )
        write_json(
            args.output,
            {
                "similarity_threshold": args.similarity_threshold,
                "groups": [item.to_dict() for item in groups],
            },
        )
        print(json.dumps({"group_count": len(groups)}, sort_keys=True))
        return 0
    if args.command == "vision-create-labeling-tasks":
        records = [
            IngestedImage.from_dict(value)
            for value in read_jsonl(args.ingestion_directory / "images.jsonl")
        ]
        values = create_labeling_tasks(
            records,
            args.task_types,
            instructions_version=args.instructions_version,
            label_vocabulary_version=args.label_vocabulary_version,
            assignee=args.assignee,
            priority=args.priority,
        )
        write_jsonl(args.output, (item.to_dict() for item in values))
        print(json.dumps({"task_count": len(values)}, sort_keys=True))
        return 0
    if args.command == "vision-validate-annotations":
        revisions = [
            AnnotationRevision.from_dict(value)
            for value in read_jsonl(args.annotations)
        ]
        vocabulary = (
            json.loads(args.label_vocabulary.read_text(encoding="utf-8-sig"))
            if args.label_vocabulary
            else None
        )
        report = validate_annotations(revisions, label_vocabulary=vocabulary)
        write_json(args.output, report.to_dict())
        print(json.dumps(report.to_dict(), ensure_ascii=False, sort_keys=True))
        return 0 if report.valid else 1
    if args.command == "vision-build-dataset-version":
        manifest = build_dataset_version(
            args.ingestion_directory,
            args.annotation_directory,
            args.output,
            version=args.version,
            seed=args.seed,
            privacy_mode=args.privacy_mode,
        )
        print(manifest)
        return 0
    if args.command == "vision-export-onnx":
        from vision_ai.pytorch_training import export_pytorch_checkpoint

        metadata = export_pytorch_checkpoint(
            args.run_directory / args.checkpoint,
            args.output,
            opset=args.opset,
            dynamic_batch=args.dynamic_batch,
        )
        write_json(args.output.with_suffix(".metadata.json"), metadata)
        print(args.output)
        return 0
    if args.command == "vision-predict":
        backend = _create_cli_backend(args)
        result = InferenceRunner(
            VisionPipeline(backend),
            fail_fast=args.fail_fast,
            validate_images=True,
            root=args.root if args.root is not None else args.input.parent,
        ).run(read_records(args.input))
        write_jsonl(args.output, (item.to_dict() for item in result.outputs))
        if args.errors is not None:
            write_jsonl(args.errors, (item.to_dict() for item in result.failures))
        print(json.dumps(result.summary.to_dict(), ensure_ascii=False, sort_keys=True))
        return 1 if result.failures else 0
    if args.command == "vision-predict-image":
        backend = _create_cli_backend(args)
        result = InferenceRunner(
            VisionPipeline(backend),
            fail_fast=args.fail_fast,
            validate_images=True,
        ).predict_image(args.image, image_id=args.image_id)
        write_jsonl(args.output, (item.to_dict() for item in result.outputs))
        print(json.dumps(result.summary.to_dict(), ensure_ascii=False, sort_keys=True))
        return 1 if result.failures else 0
    raise AssertionError("unreachable")


def _create_cli_backend(args: argparse.Namespace):
    options: dict[str, object] = {}
    backend_name = args.backend.strip().lower()
    if backend_name == "onnx":
        if args.model is None:
            raise ValueError("--model is required for the onnx backend")
        options["model_path"] = args.model
        if args.providers:
            options["providers"] = tuple(args.providers)
        if args.deployment_profile:
            options["deployment_profile"] = args.deployment_profile
    elif args.model is not None or args.providers or args.deployment_profile:
        raise ValueError(
            "--model, --provider, and --deployment-profile are only valid "
            "for the onnx backend"
        )
    if args.model_version:
        options["model_version"] = args.model_version
    return create_backend(args.backend, **options)


def _database_error(
    message: str, exit_code: int, *, json_output: bool
) -> int:
    if json_output:
        print(
            json.dumps(
                {"status": "error", "error": {"message": message}},
                ensure_ascii=False,
                sort_keys=True,
            )
        )
    else:
        print(f"Error: {message}")
    return exit_code


def _run_database_test(args: argparse.Namespace) -> int:
    try:
        config = DatabaseConfig.from_environment()
    except DatabaseConfigurationError as exc:
        return _database_error(str(exc), 2, json_output=args.json)
    try:
        result = test_database_connection(config)
    except DatabaseConnectionError:
        return _database_error(
            "Database connection failed. Verify configuration and network access.",
            3,
            json_output=args.json,
        )
    except DatabaseQueryError:
        return _database_error(
            "Database connection query failed.",
            4,
            json_output=args.json,
        )
    if args.json:
        print(json.dumps(result.to_dict(), ensure_ascii=False, sort_keys=True))
    else:
        print("Database connection successful")
        print(f"Host: {result.host}")
        print(f"Database: {result.database}")
        print(f"User: {result.user}")
        print(f"PostgreSQL version: {result.postgres_version}")
        print(f"SSL mode: {result.sslmode}")
    return 0


def _run_database_inspection(args: argparse.Namespace) -> int:
    try:
        config = DatabaseConfig.from_environment()
    except DatabaseConfigurationError as exc:
        return _database_error(str(exc), 2, json_output=args.json)
    try:
        report = inspect_database(
            config,
            schema=args.schema,
            table=args.table,
            include_views=args.include_views,
        )
    except DatabaseConnectionError:
        return _database_error(
            "Database connection failed. Verify configuration and network access.",
            3,
            json_output=args.json,
        )
    except DatabaseQueryError:
        return _database_error(
            "Database catalog inspection failed.",
            4,
            json_output=args.json,
        )
    value = report.to_dict(top_candidates=args.top_candidates)
    write_json(args.output, value)
    summary_path = args.output.with_name("backupdb-summary.txt")
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(
        report.summary_text(top_candidates=args.top_candidates),
        encoding="utf-8",
    )
    if args.json:
        print(json.dumps(value, ensure_ascii=False, sort_keys=True))
    else:
        summary = value["summary"]
        print("Database inspection successful")
        print(f"Database: {report.database}")
        print(f"User: {report.user}")
        print(f"SSL mode: {report.sslmode}")
        print()
        print(f"Schemas: {summary['schema_count']}")
        print(f"Tables: {summary['table_count']}")
        print(f"Views: {summary['view_count']}")
        print()
        print("Image/label candidates:")
        candidates = report.candidates[:args.top_candidates]
        if candidates:
            for candidate in candidates:
                print(
                    f"{candidate.schema}.{candidate.table} "
                    f"(score={candidate.score}; {', '.join(candidate.categories)})"
                )
        else:
            print("(none)")
        print()
        print(f"JSON report: {args.output}")
        print(f"Text summary: {summary_path}")
    return 0


def _run_db_defect_dataset(args: argparse.Namespace) -> int:
    try:
        config = DatabaseConfig.from_environment()
        ratios = SplitRatios(args.train, args.validation, args.test)
    except (DatabaseConfigurationError, ValueError) as exc:
        return _database_error(str(exc), 2, json_output=False)
    if args.output.exists():
        return _database_error(
            f"output directory already exists: {args.output}", 2, json_output=False
        )
    try:
        rows = extract_defect_dataset_rows(config, limit=args.limit)
    except DatabaseConnectionError:
        return _database_error(
            "Database connection failed. Verify configuration and network access.",
            3,
            json_output=False,
        )
    except DatabaseQueryError:
        return _database_error(
            "Read-only defect dataset extraction failed.", 4, json_output=False
        )
    try:
        items = defect_rows_to_dataset(rows, dataset_version=args.version)
        splits = group_stratified_split(
            (item.record for item in items), ratios, seed=args.seed
        )
        assigned = {
            record.image_id: record
            for split in ("train", "validation", "test")
            for record in splits[split]
        }
        annotations = {item.annotation.image_id: item.annotation for item in items}
        args.output.mkdir(parents=True)
        write_records(
            args.output / "records.jsonl",
            (assigned[item.record.image_id] for item in items),
        )
        write_jsonl(
            args.output / "annotations.jsonl",
            (annotations[item.record.image_id].to_dict() for item in items),
        )
        for split_name in ("train", "validation", "test"):
            write_records(
                args.output / f"{split_name}.jsonl", splits[split_name]
            )
        label_counts: dict[str, Counter[str]] = {}
        for item in items:
            for task, label in item.annotation.classifications.items():
                label_counts.setdefault(task, Counter())[label] += 1
        manifest = {
            "dataset_version": args.version,
            "source": "public.woohaja_defect_photo_tagged",
            "read_only": True,
            "image_bytes_downloaded": False,
            "sample_count": len(items),
            "group_count": len({item.record.group_id for item in items}),
            "split_counts": {name: len(values) for name, values in splits.items()},
            "split_ratios": ratios.as_dict(),
            "random_seed": args.seed,
            "classification_tasks": [
                "area", "part", "part_detail", "work_kind", "cause"
            ],
            "label_distributions": {
                task: dict(sorted(counts.items()))
                for task, counts in sorted(label_counts.items())
            },
            "grouping_policy": "defect_id",
        }
        write_json(args.output / "dataset_manifest.json", manifest)
    except (OSError, ValueError):
        return _database_error(
            "Dataset conversion failed; inspect source labels and output path.",
            5,
            json_output=False,
        )
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    print(args.output / "dataset_manifest.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
