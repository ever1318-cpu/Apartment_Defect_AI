from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest

from data_engineering.io import write_json, write_jsonl
from vision_ai.colab_workflow import export_colab_bundle, import_colab_result


def _fixture(tmp_path: Path) -> tuple[Path, Path, Path]:
    source = tmp_path / "source"
    spec_dir = tmp_path / "training"
    project = tmp_path / "project"
    source.mkdir()
    spec_dir.mkdir()
    (project / "python" / "sample").mkdir(parents=True)
    (project / "python" / "sample" / "__init__.py").write_text("", encoding="utf-8")
    (project / "pyproject.toml").write_text("[project]\nname='sample'\nversion='1'\n", encoding="utf-8")
    records = []
    for split in ("train", "validation", "test"):
        image_id = f"image-{split}"
        records.append(
            {
                "image_id": image_id,
                "image_path": f"https://example.invalid/{image_id}.jpg",
            }
        )
        write_jsonl(
            spec_dir / f"{split}.jsonl",
            [
                {
                    "image_id": image_id,
                    "image_path": f"C:/secret/images/{image_id}.jpg",
                    "paired_image_path": None,
                }
            ],
        )
    write_jsonl(source / "records.jsonl", records)
    write_json(spec_dir / "label_mapping.json", {"tasks": {}})
    write_json(
        spec_dir / "training_spec.json",
        {
            "dataset_version": "test",
            "tasks": {
                "classification": True,
                "detection": False,
                "severity": False,
                "classification_tasks": ["part"],
            },
            "split_paths": {
                split: f"{split}.jsonl"
                for split in ("train", "validation", "test")
            },
            "label_mapping_path": "label_mapping.json",
            "image_preprocessing": {},
            "augmentation": {},
            "batch_size": 2,
            "epochs": 1,
            "learning_rate": 0.001,
            "random_seed": 42,
            "output_directory": "runs",
            "model_artifact_name": "model.onnx",
        },
    )
    return source, spec_dir / "training_spec.json", project


def test_export_colab_bundle_uses_urls_and_redacts_local_paths(tmp_path: Path):
    source, spec, project = _fixture(tmp_path)
    output = tmp_path / "bundle.zip"
    export_colab_bundle(source, spec, output, project_root=project)

    assert output.with_suffix(".zip.sha256").is_file()
    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        payload = b"".join(archive.read(name) for name in names)
        manifest = json.loads(archive.read("bundle_manifest.json"))
        train = archive.read("dataset/train.jsonl").decode()
    assert "dataset/training_spec.json" in names
    assert "project/pyproject.toml" in names
    assert manifest["contains_credentials"] is False
    assert manifest["split_counts"] == {"test": 1, "train": 1, "validation": 1}
    assert "https://example.invalid/image-train.jpg" in train
    assert b"C:/secret" not in payload
    assert b"APARTMENT_DB_PASSWORD" not in payload


def test_import_colab_result_validates_completed_run(tmp_path: Path):
    archive = tmp_path / "result.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("run/run_manifest.json", '{"status":"completed"}')
        bundle.writestr("run/final_metrics.json", "{}")
        bundle.writestr("run/training_spec.json", "{}")
        bundle.writestr("run/label_mapping.json", "{}")
        bundle.writestr("run/model.onnx", "model")

    run = import_colab_result(archive, tmp_path / "imported")

    assert run.name == "run"
    imported = json.loads(
        (tmp_path / "imported" / "colab_import_manifest.json").read_text()
    )
    assert imported["status"] == "verified"
    assert len(imported["archive_sha256"]) == 64


def test_import_colab_result_rejects_path_traversal(tmp_path: Path):
    archive = tmp_path / "unsafe.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("../outside.txt", "unsafe")
    with pytest.raises(ValueError, match="unsafe archive"):
        import_colab_result(archive, tmp_path / "imported")


def test_import_colab_result_rejects_checksum_mismatch(tmp_path: Path):
    archive = tmp_path / "result.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("run/run_manifest.json", '{"status":"completed"}')
    archive.with_suffix(".zip.sha256").write_text("0" * 64 + "  result.zip\n")
    with pytest.raises(ValueError, match="SHA-256"):
        import_colab_result(archive, tmp_path / "imported")


def test_export_requires_public_url_for_every_training_sample(tmp_path: Path):
    source, spec, project = _fixture(tmp_path)
    write_jsonl(source / "records.jsonl", [])
    with pytest.raises(ValueError, match="no public source URL"):
        export_colab_bundle(
            source, spec, tmp_path / "bundle.zip", project_root=project
        )
