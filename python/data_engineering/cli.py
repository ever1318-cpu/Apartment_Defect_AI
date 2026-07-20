"""Command-line interface for repeatable dataset operations."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path
from typing import Sequence

from .etl.legacy_import import deduplicate_records, import_legacy_csv
from .io import read_jsonl, read_records, write_json, write_jsonl, write_records
from .models import SplitRatios
from .splitters.group_stratified import group_stratified_split
from .validators.manifest import validate_records
from .versioning.manifest import build_manifest
from vision_ai.backends import create_backend
from vision_ai.evaluation import EvaluationConfig, evaluate_predictions
from vision_ai.evaluation_models import GroundTruthAnnotation
from vision_ai.inference import InferenceRunner
from vision_ai.models import VisionPrediction
from vision_ai.model_package import build_model_package, validate_model_package
from vision_ai.pipeline import VisionPipeline
from vision_ai.training import TrainingRunner, load_training_backend
from vision_ai.training_dataset import build_training_dataset
from vision_ai.training_models import TrainingSpec, TrainingTasks
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

    train = commands.add_parser(
        "vision-train", help="execute a framework-neutral training workflow"
    )
    train.add_argument("spec", type=Path)
    train.add_argument("run_directory", type=Path)
    train.add_argument("--backend", default="reference")
    train.add_argument(
        "--device", choices=("auto", "cpu", "cuda"), default="auto"
    )

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
    if args.command == "vision-train":
        spec = TrainingSpec.from_dict(
            json.loads(args.spec.read_text(encoding="utf-8-sig"))
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


if __name__ == "__main__":
    raise SystemExit(main())
