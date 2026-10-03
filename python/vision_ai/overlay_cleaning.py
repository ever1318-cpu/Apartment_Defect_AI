"""Conservative annotation-overlay masking for defect photographs."""

from __future__ import annotations

import hashlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Iterable

from data_engineering.models import ImageRecord


@dataclass(frozen=True, slots=True)
class OverlayCleaningResult:
    image_id: str
    status: str
    coverage_ratio: float
    review_required: bool
    mask_path: str | None
    cleaned_path: str | None
    error_type: str | None = None


def clean_overlay_images(
    records: Iterable[ImageRecord],
    output: str | Path,
    *,
    workers: int = 8,
    review_coverage: float = 0.25,
) -> tuple[tuple[ImageRecord, ...], tuple[OverlayCleaningResult, ...]]:
    """Create masks and cleaned variants while preserving original files."""
    if workers < 1 or not 0 <= review_coverage <= 1:
        raise ValueError("invalid cleaning settings")
    try:
        import numpy
        from PIL import Image, ImageFilter
    except ImportError as exc:
        raise RuntimeError("overlay cleaning requires Pillow and numpy") from exc
    root = Path(output)
    image_root = root / "cleaned"
    mask_root = root / "masks"
    image_root.mkdir(parents=True, exist_ok=True)
    mask_root.mkdir(parents=True, exist_ok=True)

    def clean(record: ImageRecord):
        token = hashlib.sha256(record.image_id.encode()).hexdigest()
        cleaned_path = image_root / f"{token}.jpg"
        mask_path = mask_root / f"{token}.png"
        try:
            with Image.open(record.image_path) as source:
                rgb = source.convert("RGB")
                values = numpy.asarray(rgb, dtype=numpy.float32) / 255.0
                maximum = values.max(axis=2)
                minimum = values.min(axis=2)
                delta = maximum - minimum
                saturation = numpy.divide(
                    delta,
                    maximum,
                    out=numpy.zeros_like(delta),
                    where=maximum > 0,
                )
                red = values[:, :, 0]
                green = values[:, :, 1]
                blue = values[:, :, 2]
                marker = (
                    (saturation >= 0.72)
                    & (maximum >= 0.58)
                    & (
                        ((red > green * 1.35) & (red > blue * 1.35))
                        | ((green > red * 1.35) & (green > blue * 1.25))
                        | ((blue > red * 1.35) & (blue > green * 1.25))
                        | ((red > 0.72) & (green > 0.62) & (blue < 0.35))
                    )
                )
                mask = Image.fromarray((marker * 255).astype("uint8"), mode="L")
                mask = mask.filter(ImageFilter.MaxFilter(5))
                mask_array = numpy.asarray(mask)
                coverage = float((mask_array > 0).mean())
                blurred = rgb.filter(ImageFilter.GaussianBlur(radius=12))
                cleaned = Image.composite(blurred, rgb, mask)
                cleaned.save(cleaned_path, format="JPEG", quality=95)
                mask.save(mask_path, format="PNG")
            metadata = {
                **dict(record.metadata),
                "original_image_path": record.image_path,
                "overlay_mask_path": str(mask_path.resolve()),
                "overlay_coverage_ratio": coverage,
                "cleaning_method": "conservative_color_mask_blur",
                "cleaning_review_required": coverage >= review_coverage,
            }
            return (
                replace(
                    record,
                    image_path=str(cleaned_path.resolve()),
                    metadata=metadata,
                ),
                OverlayCleaningResult(
                    record.image_id,
                    "cleaned",
                    coverage,
                    coverage >= review_coverage,
                    str(mask_path.resolve()),
                    str(cleaned_path.resolve()),
                ),
            )
        except Exception as exc:
            return (
                None,
                OverlayCleaningResult(
                    record.image_id,
                    "error",
                    0,
                    True,
                    None,
                    None,
                    type(exc).__name__,
                ),
            )

    with ThreadPoolExecutor(max_workers=workers) as executor:
        results = tuple(executor.map(clean, tuple(records)))
    return (
        tuple(record for record, _ in results if record is not None),
        tuple(result for _, result in results),
    )
