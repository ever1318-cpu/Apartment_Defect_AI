from pathlib import Path

from vision_ai.field_app import _media_file_path


def test_gallery_media_path_uses_exact_object_key(tmp_path: Path) -> None:
    photo = tmp_path / "101_1501_CLOSE_P00001.jpg"
    photo.write_bytes(b"image")

    assert _media_file_path(tmp_path, "field-media/101_1501_CLOSE_P00001.jpg", {}) == photo


def test_gallery_media_path_recovers_legacy_separator_normalized_name(tmp_path: Path) -> None:
    photo = tmp_path / "공용부_지하주차장_P00212.jpg"
    photo.write_bytes(b"image")

    result = _media_file_path(
        tmp_path,
        "field-media/공용부·지하주차장_P00212.jpg",
        {"local_photo_id": 212},
    )

    assert result == photo


def test_gallery_media_path_rejects_ambiguous_legacy_photo_id(tmp_path: Path) -> None:
    (tmp_path / "a_P00212.jpg").write_bytes(b"a")
    (tmp_path / "b_P00212.jpg").write_bytes(b"b")

    assert _media_file_path(tmp_path, "field-media/missing.jpg", {"local_photo_id": 212}) is None
