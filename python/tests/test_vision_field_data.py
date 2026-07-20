import json
import struct
import zlib
from dataclasses import replace
from pathlib import Path

import pytest

from data_engineering.cli import main
from data_engineering.io import read_jsonl, write_json, write_jsonl
from vision_ai.evaluation_models import GroundTruthAnnotation
from vision_ai.field_data import (
    build_dataset_version,
    check_image_quality,
    create_labeling_tasks,
    create_masked_derivative,
    find_duplicates,
    ingest_images,
    validate_annotations,
)
from vision_ai.field_data_models import (
    AnnotationRevision,
    IngestedImage,
    PrivacyMask,
)
from vision_ai.models import BoundingBox, DefectDetection, PolygonMask


NOW = "2026-07-20T00:00:00+00:00"


def _png(width=80, height=80, value=120) -> bytes:
    rows = b"".join(
        b"\x00" + bytes((value, value, value)) * width for _ in range(height)
    )

    def chunk(name, data):
        return (
            struct.pack(">I", len(data))
            + name
            + data
            + struct.pack(">I", zlib.crc32(name + data) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


def _batch(tmp_path: Path, count=3) -> tuple[Path, tuple[IngestedImage, ...]]:
    source = tmp_path / "source"
    source.mkdir()
    for index in range(count):
        (source / f"image-{index}.png").write_bytes(_png(value=80 + index * 40))
    output = tmp_path / "ingestion"
    records = ingest_images(
        source,
        output,
        source_batch="batch-001",
        operator="operator-1",
        ingested_at=NOW,
    )
    return output, records


def _revision(image_id: str, *, status="approved", revision=1, annotator="a"):
    return AnnotationRevision(
        GroundTruthAnnotation(
            image_id=image_id,
            dataset_version="dataset-001",
            classifications={
                "space": "bathroom",
                "trade": "finishing",
                "component": "wall",
            },
            detections=(
                DefectDetection(
                    "crack",
                    1.0,
                    BoundingBox(0.1, 0.1, 0.5, 0.5),
                    severity="low",
                ),
            ),
        ),
        status,
        annotator,
        "reviewer" if status == "approved" else None,
        0.9,
        "",
        revision,
        NOW,
        NOW,
        "needs correction" if status == "rejected" else None,
        ({"action": status, "at": NOW, "actor": annotator},),
    )


def test_ingestion_content_addressing_errors_and_relative_paths(tmp_path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    content = _png()
    (source / "one.png").write_bytes(content)
    (source / "duplicate.png").write_bytes(content)
    (source / "bad.png").write_bytes(b"not-an-image")
    output = tmp_path / "batch"

    records = ingest_images(
        source, output, source_batch="batch", ingested_at=NOW
    )

    assert len(records) == 1
    assert records[0].image_id == records[0].content_sha256
    assert not Path(records[0].stored_path).is_absolute()
    errors = list(read_jsonl(output / "errors.jsonl"))
    assert {item["error_type"] for item in errors} == {"ValueError"}
    assert len(errors) == 2


def test_ingestion_rejects_symlink_when_supported(tmp_path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    target = tmp_path / "outside.png"
    target.write_bytes(_png())
    link = source / "linked.png"
    try:
        link.symlink_to(target)
    except OSError:
        pytest.skip("symbolic links are unavailable")
    output = tmp_path / "batch"
    assert ingest_images(source, output, source_batch="batch") == ()
    assert "symbolic" in list(read_jsonl(output / "errors.jsonl"))[0]["message"]


def test_manifest_path_traversal_is_rejected_per_item(tmp_path) -> None:
    outside = tmp_path / "outside.png"
    outside.write_bytes(_png())
    manifest = tmp_path / "records.jsonl"
    write_jsonl(manifest, [{"image_path": "../outside.png"}])
    with pytest.raises(ValueError, match="traversal"):
        ingest_images(manifest, tmp_path / "batch", source_batch="batch")


def test_quality_corrupt_oversized_and_dimension_boundary(tmp_path) -> None:
    root, records = _batch(tmp_path, 1)
    good = check_image_quality(records, root=root)
    assert good[0].status == "warning"
    assert good[0].brightness == pytest.approx(80 / 255)
    assert "low_contrast" in good[0].issues
    oversized = check_image_quality(records, root=root, max_dimension=40)
    assert oversized[0].status == "fail"
    (root / records[0].stored_path).write_bytes(b"\x89PNG\r\n\x1a\nbroken")
    corrupt = check_image_quality(records, root=root)
    assert corrupt[0].status == "fail"


def test_exact_and_near_duplicate_groups(tmp_path) -> None:
    root, records = _batch(tmp_path, 2)
    duplicate = replace(records[0], image_id="duplicate-image")
    exact = find_duplicates((*records, duplicate), root=root)
    assert exact[0].kind == "exact"
    near = find_duplicates(records, root=root, similarity_threshold=0.0)
    assert any(group.kind == "near" for group in near)


def test_privacy_derivative_never_overwrites_source(tmp_path) -> None:
    root, records = _batch(tmp_path, 1)
    mask = PrivacyMask(
        "mask-1",
        records[0].image_id,
        "face",
        ((0.1, 0.1), (0.4, 0.1), (0.4, 0.4), (0.1, 0.4)),
        "manual",
        "operator",
        NOW,
        "approved",
    )

    def transformer(source, target, masks):
        target.write_bytes(source.read_bytes() + b"-masked")

    derivative = create_masked_derivative(
        records[0], (mask,), root / "masked", root=root, transformer=transformer
    )
    assert derivative.read_bytes().endswith(b"-masked")
    assert not (root / records[0].stored_path).read_bytes().endswith(b"-masked")
    with pytest.raises(FileExistsError):
        create_masked_derivative(
            records[0], (mask,), root / "masked", root=root, transformer=transformer
        )


def test_labeling_tasks_are_deterministic_and_duplicate_safe(tmp_path) -> None:
    _, records = _batch(tmp_path, 2)
    tasks = create_labeling_tasks(
        records,
        ("classification", "privacy_mask_review"),
        instructions_version="1",
        label_vocabulary_version="1",
        created_at=NOW,
    )
    assert len(tasks) == 4
    assert tasks[0].status == "pending"
    with pytest.raises(ValueError, match="duplicate labeling task"):
        create_labeling_tasks(
            (records[0], records[0]),
            ("classification",),
            instructions_version="1",
            label_vocabulary_version="1",
        )


def test_annotation_round_trip_unknown_label_polygon_and_agreement() -> None:
    first = _revision("image-1", annotator="first")
    assert AnnotationRevision.from_dict(first.to_dict()) == first
    second = replace(first, annotator="second", revision=2)
    report = validate_annotations(
        (first, second),
        label_vocabulary={"space": ["kitchen"]},
    )
    assert not report.valid
    assert report.agreement == 1.0
    assert "unknown_label" in {item.code for item in report.issues}

    crossing = replace(
        first,
        annotation=GroundTruthAnnotation(
            "image-2",
            detections=(
                DefectDetection(
                    "crack",
                    1,
                    BoundingBox(0, 0, 1, 1),
                    PolygonMask(((0, 0), (1, 1), (0, 1), (1, 0))),
                    "low",
                ),
            ),
        ),
    )
    assert "invalid_polygon" in {
        item.code for item in validate_annotations((crossing,)).issues
    }


def test_dataset_version_approved_only_deterministic_and_lineage(tmp_path) -> None:
    root, records = _batch(tmp_path, 4)
    annotations = tmp_path / "annotations"
    annotations.mkdir()
    revisions = [_revision(item.image_id) for item in records]
    revisions.append(
        _revision(records[-1].image_id, status="rejected", revision=2)
    )
    write_jsonl(annotations / "revisions.jsonl", (item.to_dict() for item in revisions))
    write_json(
        root / "duplicate_groups.json",
        {
            "similarity_threshold": 1,
            "groups": [
                {
                    "group_id": "group",
                    "kind": "near",
                    "canonical_image_id": records[0].image_id,
                    "image_ids": [records[0].image_id, records[1].image_id],
                    "similarity": 1,
                    "policy": "exclude_non_canonical",
                }
            ],
        },
    )
    first = tmp_path / "version-one"
    second = tmp_path / "version-two"
    first_manifest = build_dataset_version(
        root, annotations, first, version="dataset-001", seed=7
    )
    build_dataset_version(root, annotations, second, version="dataset-001", seed=7)
    first_records = list(read_jsonl(first / "records.jsonl"))
    second_records = list(read_jsonl(second / "records.jsonl"))
    assert first_records == second_records
    assert len(first_records) == 2
    assert all((first / item["image_path"]).is_file() for item in first_records)
    manifest = json.loads(first_manifest.read_text(encoding="utf-8"))
    assert manifest["source_batches"] == ["batch-001"]
    assert {item["reason"] for item in manifest["excluded"]} == {
        "annotation_not_approved",
        "non_canonical_duplicate",
    }
    groups = {}
    for item in first_records:
        groups.setdefault(item["group_id"], set()).add(item["split"])
    assert all(len(splits) == 1 for splits in groups.values())


def test_field_data_cli_end_to_end(tmp_path, capsys) -> None:
    source = tmp_path / "source"
    source.mkdir()
    for index in range(3):
        (source / f"{index}.png").write_bytes(_png(value=60 + index * 60))
    ingestion = tmp_path / "batch"
    assert main(
        [
            "vision-ingest-images",
            str(source),
            str(ingestion),
            "--source-batch",
            "batch-cli",
        ]
    ) == 0
    quality = ingestion / "quality.jsonl"
    assert main(
        [
            "vision-check-image-quality",
            str(ingestion),
            str(quality),
        ]
    ) == 0
    duplicates = ingestion / "duplicate_groups.json"
    assert main(
        ["vision-find-duplicates", str(ingestion), str(duplicates)]
    ) == 0
    tasks = tmp_path / "tasks.jsonl"
    assert main(
        [
            "vision-create-labeling-tasks",
            str(ingestion),
            str(tasks),
            "--task-type",
            "classification",
            "--instructions-version",
            "1",
            "--label-vocabulary-version",
            "1",
        ]
    ) == 0
    records = [
        IngestedImage.from_dict(value)
        for value in read_jsonl(ingestion / "images.jsonl")
    ]
    annotation_file = tmp_path / "annotations.jsonl"
    write_jsonl(annotation_file, (_revision(item.image_id).to_dict() for item in records))
    qa = tmp_path / "qa.json"
    assert main(
        ["vision-validate-annotations", str(annotation_file), str(qa)]
    ) == 0
    version = tmp_path / "version"
    assert main(
        [
            "vision-build-dataset-version",
            str(ingestion),
            str(annotation_file),
            str(version),
            "--version",
            "dataset-cli",
        ]
    ) == 0
    assert (version / "dataset_version_manifest.json").is_file()
    assert len(list(read_jsonl(tasks))) == 3
    assert capsys.readouterr().out
