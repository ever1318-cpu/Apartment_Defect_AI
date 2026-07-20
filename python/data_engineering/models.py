"""Typed domain models shared by the data-engineering pipeline."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Mapping


@dataclass(frozen=True, slots=True)
class ImageRecord:
    """Canonical metadata for a single source image."""

    image_id: str
    image_path: str
    group_id: str
    label: str
    width: int | None = None
    height: int | None = None
    source: str = "unknown"
    checksum: str | None = None
    split: str | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        for name in ("image_id", "image_path", "group_id", "label"):
            value = getattr(self, name)
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"{name} must be a non-empty string")
        for name in ("width", "height"):
            value = getattr(self, name)
            if value is not None and (not isinstance(value, int) or value <= 0):
                raise ValueError(f"{name} must be a positive integer or null")
        if self.split not in (None, "train", "validation", "test"):
            raise ValueError("split must be train, validation, test, or null")

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ImageRecord":
        """Build a record while rejecting unknown top-level fields."""
        known = {field.name for field in cls.__dataclass_fields__.values()}
        unknown = set(value) - known
        if unknown:
            raise ValueError(f"unknown ImageRecord fields: {sorted(unknown)}")
        return cls(**dict(value))

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @property
    def suffix(self) -> str:
        return Path(self.image_path).suffix.lower()


@dataclass(frozen=True, slots=True)
class SplitRatios:
    train: float = 0.8
    validation: float = 0.1
    test: float = 0.1

    def __post_init__(self) -> None:
        values = (self.train, self.validation, self.test)
        if any(value < 0 for value in values):
            raise ValueError("split ratios cannot be negative")
        if abs(sum(values) - 1.0) > 1e-9:
            raise ValueError("split ratios must sum to 1")

    def as_dict(self) -> dict[str, float]:
        return {"train": self.train, "validation": self.validation, "test": self.test}
