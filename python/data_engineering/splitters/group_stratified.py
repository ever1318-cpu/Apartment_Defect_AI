"""Deterministic greedy group-stratified splitting."""

from __future__ import annotations

import hashlib
from collections import Counter, defaultdict
from dataclasses import replace
from typing import Iterable, Sequence

from ..models import ImageRecord, SplitRatios

SPLITS = ("train", "validation", "test")


def _stable_tiebreak(seed: int, *parts: object) -> int:
    payload = "|".join(map(str, (seed, *parts))).encode("utf-8")
    return int.from_bytes(hashlib.sha256(payload).digest()[:8], "big")


def group_stratified_split(
    records: Sequence[ImageRecord] | Iterable[ImageRecord],
    ratios: SplitRatios | None = None,
    *,
    seed: int = 42,
) -> dict[str, list[ImageRecord]]:
    """Assign complete groups while approximating global label and size ratios."""
    items = list(records)
    ratios = ratios or SplitRatios()
    result: dict[str, list[ImageRecord]] = {name: [] for name in SPLITS}
    if not items:
        return result

    seen_ids: set[str] = set()
    groups: dict[str, list[ImageRecord]] = defaultdict(list)
    for record in items:
        if record.image_id in seen_ids:
            raise ValueError(f"duplicate image_id: {record.image_id}")
        seen_ids.add(record.image_id)
        groups[record.group_id].append(record)

    total_labels = Counter(record.label for record in items)
    target_ratio = ratios.as_dict()
    target_size = {name: len(items) * target_ratio[name] for name in SPLITS}
    target_labels = {
        name: {label: count * target_ratio[name] for label, count in total_labels.items()}
        for name in SPLITS
    }
    current_size = Counter()
    current_labels: dict[str, Counter[str]] = {name: Counter() for name in SPLITS}

    ordered_groups = sorted(
        groups.items(),
        key=lambda item: (
            -len(item[1]),
            -max(Counter(record.label for record in item[1]).values()),
            _stable_tiebreak(seed, item[0]),
        ),
    )
    for group_id, group in ordered_groups:
        labels = Counter(record.label for record in group)

        def cost(split: str) -> tuple[float, float, int]:
            label_error = sum(
                (
                    current_labels[name][label]
                    + (labels[label] if name == split else 0)
                    - target_labels[name][label]
                )
                ** 2
                / max(total_labels[label], 1)
                for name in SPLITS
                for label in total_labels
            )
            size_error = sum(
                (
                    current_size[name]
                    + (len(group) if name == split else 0)
                    - target_size[name]
                )
                ** 2
                / max(len(items), 1)
                for name in SPLITS
            )
            return (
                label_error + size_error,
                current_size[split] / max(target_size[split], 1e-12),
                _stable_tiebreak(seed, group_id, split),
            )

        eligible = [name for name in SPLITS if target_ratio[name] > 0]
        chosen = min(eligible, key=cost)
        assigned = [replace(record, split=chosen) for record in group]
        result[chosen].extend(assigned)
        current_size[chosen] += len(group)
        current_labels[chosen].update(labels)

    for split in SPLITS:
        result[split].sort(key=lambda record: record.image_id)
    return result
