"""Rank candidates by uncertainty with optional group diversity."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence


@dataclass(frozen=True, slots=True)
class Candidate:
    image_id: str
    probabilities: Sequence[float]
    group_id: str | None = None


def entropy(probabilities: Sequence[float]) -> float:
    if not probabilities:
        raise ValueError("probabilities cannot be empty")
    if any(value < 0 for value in probabilities):
        raise ValueError("probabilities cannot be negative")
    total = sum(probabilities)
    if total <= 0:
        raise ValueError("probabilities must have a positive sum")
    normalized = (value / total for value in probabilities)
    return -sum(value * math.log(value) for value in normalized if value > 0)


def select_candidates(
    candidates: Iterable[Candidate],
    budget: int,
    *,
    max_per_group: int | None = None,
) -> list[Candidate]:
    if budget < 0:
        raise ValueError("budget cannot be negative")
    if max_per_group is not None and max_per_group <= 0:
        raise ValueError("max_per_group must be positive")
    ranked = sorted(candidates, key=lambda item: (-entropy(item.probabilities), item.image_id))
    selected: list[Candidate] = []
    group_counts: dict[str, int] = {}
    for candidate in ranked:
        group = candidate.group_id or candidate.image_id
        if max_per_group is not None and group_counts.get(group, 0) >= max_per_group:
            continue
        selected.append(candidate)
        group_counts[group] = group_counts.get(group, 0) + 1
        if len(selected) == budget:
            break
    return selected


def select_from_predictions(
    predictions: Mapping[str, Sequence[float]], budget: int
) -> list[str]:
    return [
        candidate.image_id
        for candidate in select_candidates(
            (Candidate(image_id, values) for image_id, values in predictions.items()), budget
        )
    ]
