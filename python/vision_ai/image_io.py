"""Dependency-free validation for image inputs used by inference runtimes."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


_EXTENSION_FORMATS = {
    ".jpeg": "jpeg",
    ".jpg": "jpeg",
    ".png": "png",
    ".tif": "tiff",
    ".tiff": "tiff",
    ".webp": "webp",
}


@dataclass(frozen=True, slots=True)
class ImageFileInfo:
    path: Path
    format: str
    size_bytes: int


def resolve_image_path(
    image_path: str | Path, *, root: str | Path | None = None
) -> Path:
    candidate = Path(image_path)
    if root is not None and not candidate.is_absolute():
        candidate = Path(root) / candidate
    return candidate.resolve()


def inspect_image_file(
    image_path: str | Path, *, root: str | Path | None = None
) -> ImageFileInfo:
    """Validate existence, supported extension, and basic file signature."""
    path = resolve_image_path(image_path, root=root)
    expected_format = _EXTENSION_FORMATS.get(path.suffix.lower())
    if expected_format is None:
        raise ValueError(f"unsupported image extension: {path.suffix or '<none>'}")
    if not path.is_file():
        raise FileNotFoundError(f"image file does not exist: {path}")
    try:
        with path.open("rb") as stream:
            header = stream.read(16)
    except OSError as exc:
        raise OSError(f"cannot read image file: {path}") from exc
    if not header:
        raise ValueError(f"image file is empty: {path}")
    detected_format = _detect_format(header)
    if detected_format is None:
        raise ValueError(f"unrecognized image file signature: {path}")
    if detected_format != expected_format:
        raise ValueError(
            f"image extension expects {expected_format}, found {detected_format}: {path}"
        )
    return ImageFileInfo(path, detected_format, path.stat().st_size)


def _detect_format(header: bytes) -> str | None:
    if header.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if header.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    if len(header) >= 12 and header[:4] == b"RIFF" and header[8:12] == b"WEBP":
        return "webp"
    if header.startswith((b"II*\x00", b"MM\x00*")):
        return "tiff"
    return None
