import pytest

from vision_ai.models import BoundingBox, Classification, DefectDetection
from vision_ai.postprocessing import (
    assign_severity,
    intersection_over_union,
    non_maximum_suppression,
    top_k_classifications,
)


def detection(label: str, confidence: float, box: tuple[float, ...]) -> DefectDetection:
    return DefectDetection(label, confidence, BoundingBox(*box))


def test_top_k_is_filtered_and_deterministic() -> None:
    result = top_k_classifications(
        [
            Classification("wall", 0.8),
            Classification("bathroom", 0.8),
            Classification("kitchen", 0.1),
        ],
        minimum_confidence=0.2,
        limit=2,
    )
    assert [item.label for item in result] == ["bathroom", "wall"]


def test_nms_suppresses_overlapping_detection_of_same_class() -> None:
    result = non_maximum_suppression(
        [
            detection("crack", 0.9, (0, 0, 0.5, 0.5)),
            detection("crack", 0.8, (0.02, 0.02, 0.52, 0.52)),
            detection("leak", 0.7, (0.02, 0.02, 0.52, 0.52)),
        ],
        iou_threshold=0.5,
    )
    assert [(item.label, item.confidence) for item in result] == [
        ("crack", 0.9),
        ("leak", 0.7),
    ]


def test_iou_for_disjoint_boxes_is_zero() -> None:
    assert intersection_over_union(
        BoundingBox(0, 0, 0.2, 0.2), BoundingBox(0.8, 0.8, 1, 1)
    ) == 0


@pytest.mark.parametrize(
    ("box", "expected"),
    [
        ((0, 0, 0.1, 0.1), "low"),
        ((0, 0, 0.2, 0.2), "medium"),
        ((0, 0, 0.5, 0.5), "high"),
    ],
)
def test_severity_is_derived_from_affected_area(
    box: tuple[float, ...], expected: str
) -> None:
    assert assign_severity(detection("crack", 0.9, box)).severity == expected
