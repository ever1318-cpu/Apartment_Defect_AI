"""Serializable and deterministic augmentation policy selection."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any, Mapping


@dataclass(frozen=True, slots=True)
class TransformPolicy:
    name: str
    probability: float
    parameters: Mapping[str, Any]

    def __post_init__(self) -> None:
        if not self.name:
            raise ValueError("transform name cannot be empty")
        if not 0 <= self.probability <= 1:
            raise ValueError("probability must be between 0 and 1")


DEFAULT_POLICY = (
    TransformPolicy("horizontal_flip", 0.5, {}),
    TransformPolicy("brightness_contrast", 0.4, {"limit": 0.2}),
    TransformPolicy("rotate", 0.3, {"degrees": 8}),
    TransformPolicy("gaussian_noise", 0.15, {"variance": [5, 20]}),
)


def _unit_interval(seed: int, sample_id: str, transform: str) -> float:
    digest = hashlib.sha256(f"{seed}|{sample_id}|{transform}".encode()).digest()
    return int.from_bytes(digest[:8], "big") / (2**64 - 1)


def select_transforms(
    sample_id: str,
    *,
    seed: int = 42,
    policy: tuple[TransformPolicy, ...] = DEFAULT_POLICY,
) -> list[TransformPolicy]:
    return [
        transform
        for transform in policy
        if _unit_interval(seed, sample_id, transform.name) < transform.probability
    ]


def policy_from_config(config: list[Mapping[str, Any]]) -> tuple[TransformPolicy, ...]:
    return tuple(
        TransformPolicy(
            name=str(item["name"]),
            probability=float(item.get("probability", 1)),
            parameters=dict(item.get("parameters", {})),
        )
        for item in config
    )
