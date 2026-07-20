import pytest

from vision_ai.models import (
    BoundingBox,
    Classification,
    DefectDetection,
    ImageQuality,
    PolygonMask,
    VisionPrediction,
)


def test_prediction_round_trip_preserves_nested_values() -> None:
    prediction = VisionPrediction(
        image_id="image-1",
        model_version="vision-1",
        quality=ImageQuality(0.9, True),
        classifications={"space": (Classification("bathroom", 0.8),)},
        detections=(
            DefectDetection(
                "crack",
                0.95,
                BoundingBox(0.1, 0.2, 0.5, 0.7),
                PolygonMask(((0.1, 0.2), (0.5, 0.2), (0.5, 0.7))),
                "medium",
            ),
        ),
    )

    assert VisionPrediction.from_dict(prediction.to_dict()) == prediction


@pytest.mark.parametrize(
    "box",
    [
        (-0.1, 0, 1, 1),
        (0, 0, 1.1, 1),
        (0.5, 0, 0.5, 1),
        (0, 0.5, 1, 0.4),
    ],
)
def test_bounding_box_rejects_invalid_coordinates(box: tuple[float, ...]) -> None:
    with pytest.raises(ValueError):
        BoundingBox(*box)


def test_polygon_area_uses_normalized_coordinates() -> None:
    mask = PolygonMask(((0, 0), (1, 0), (1, 1), (0, 1)))
    assert mask.area == pytest.approx(1)
