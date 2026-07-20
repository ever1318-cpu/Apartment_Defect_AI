from collections import Counter

import pytest

from data_engineering.models import ImageRecord, SplitRatios
from data_engineering.splitters.group_stratified import group_stratified_split


def record(number: int, group: str, label: str) -> ImageRecord:
    return ImageRecord(
        image_id=f"image-{number}",
        image_path=f"images/{number}.jpg",
        group_id=group,
        label=label,
    )


def sample_records() -> list[ImageRecord]:
    return [
        record(number, f"apartment-{number // 2}", "crack" if number % 3 else "leak")
        for number in range(30)
    ]


def test_split_is_deterministic_and_has_no_group_leakage() -> None:
    first = group_stratified_split(sample_records(), seed=7)
    second = group_stratified_split(sample_records(), seed=7)
    assert first == second

    split_by_group: dict[str, set[str]] = {}
    for split, records in first.items():
        for item in records:
            assert item.split == split
            split_by_group.setdefault(item.group_id, set()).add(split)
    assert all(len(splits) == 1 for splits in split_by_group.values())


def test_split_preserves_all_records_and_approximates_ratios() -> None:
    result = group_stratified_split(sample_records(), SplitRatios(0.6, 0.2, 0.2))
    flattened = [item for records in result.values() for item in records]
    assert {item.image_id for item in flattened} == {
        item.image_id for item in sample_records()
    }
    assert all(result[name] for name in ("train", "validation", "test"))
    assert abs(len(result["train"]) - 18) <= 4

    global_labels = Counter(item.label for item in flattened)
    train_labels = Counter(item.label for item in result["train"])
    for label, count in global_labels.items():
        assert abs(train_labels[label] / len(result["train"]) - count / len(flattened)) < 0.2


def test_zero_ratio_split_remains_empty() -> None:
    result = group_stratified_split(sample_records(), SplitRatios(0.8, 0.2, 0))
    assert result["test"] == []


def test_duplicate_image_id_is_rejected() -> None:
    duplicate = record(1, "a", "crack")
    with pytest.raises(ValueError, match="duplicate image_id"):
        group_stratified_split([duplicate, duplicate])


def test_invalid_ratios_are_rejected() -> None:
    with pytest.raises(ValueError, match="sum to 1"):
        SplitRatios(0.7, 0.2, 0.2)
