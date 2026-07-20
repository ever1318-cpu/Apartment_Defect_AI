import json

import pytest

from data_engineering.cli import main
from data_engineering.io import write_jsonl
from vision_ai.evaluation import EvaluationConfig, evaluate_predictions
from vision_ai.evaluation_models import EvaluationReport, GroundTruthAnnotation
from vision_ai.models import (
    BoundingBox,
    Classification,
    DefectDetection,
    ImageQuality,
    VisionPrediction,
)


def detection(
    label: str = "crack",
    box: tuple[float, float, float, float] = (0.1, 0.1, 0.5, 0.5),
    *,
    confidence: float = 1.0,
    severity: str | None = "low",
) -> DefectDetection:
    return DefectDetection(
        label,
        confidence,
        BoundingBox(*box),
        severity=severity,
    )


def truth(
    image_id: str = "image-1",
    *,
    label: str = "bathroom",
    detections: tuple[DefectDetection, ...] | None = None,
) -> GroundTruthAnnotation:
    return GroundTruthAnnotation(
        image_id=image_id,
        dataset_version="dataset-7",
        classifications={"space": label},
        detections=detections if detections is not None else (detection(),),
    )


def prediction(
    image_id: str = "image-1",
    *,
    label: str = "bathroom",
    confidence: float = 0.9,
    detections: tuple[DefectDetection, ...] | None = None,
) -> VisionPrediction:
    return VisionPrediction(
        image_id=image_id,
        model_version="model-3",
        quality=ImageQuality(0.9, True),
        classifications={"space": (Classification(label, confidence),)},
        detections=detections if detections is not None else (detection(confidence=0.9),),
    )


def config(**values) -> EvaluationConfig:
    return EvaluationConfig(
        evaluated_at="2026-07-20T00:00:00+00:00",
        **values,
    )


def test_perfect_prediction_has_perfect_metrics() -> None:
    report = evaluate_predictions([truth()], [prediction()], config())

    classification = report.classification["space"]
    assert classification.accuracy == 1
    assert classification.macro_f1 == 1
    assert classification.confusion_matrix == {"bathroom": {"bathroom": 1}}
    assert report.detection.true_positive == 1
    assert report.detection.false_positive == 0
    assert report.detection.false_negative == 0
    assert report.detection.f1 == 1
    assert report.severity.accuracy == 1
    assert report.dataset_version == "dataset-7"
    assert report.model_version == "model-3"


def test_misclassification_and_confidence_threshold_are_counted() -> None:
    wrong = evaluate_predictions(
        [truth()], [prediction(label="kitchen")], config()
    )
    below_threshold = evaluate_predictions(
        [truth()],
        [prediction(confidence=0.2)],
        config(confidence_threshold=0.25),
    )

    assert wrong.classification["space"].accuracy == 0
    assert wrong.classification["space"].labels["bathroom"].false_negative == 1
    assert wrong.classification["space"].labels["kitchen"].false_positive == 1
    assert below_threshold.classification["space"].confusion_matrix == {
        "bathroom": {"__none__": 1}
    }


@pytest.mark.parametrize(
    "predicted_detection",
    [
        detection(box=(0.7, 0.7, 0.9, 0.9), confidence=0.9),
        detection(label="leak", confidence=0.9),
    ],
)
def test_detection_requires_iou_and_matching_class(
    predicted_detection: DefectDetection,
) -> None:
    report = evaluate_predictions(
        [truth()],
        [prediction(detections=(predicted_detection,))],
        config(iou_threshold=0.5),
    )

    assert report.detection.true_positive == 0
    assert report.detection.false_positive == 1
    assert report.detection.false_negative == 1


def test_detection_below_confidence_threshold_becomes_false_negative() -> None:
    report = evaluate_predictions(
        [truth()],
        [prediction(detections=(detection(confidence=0.2),))],
        config(confidence_threshold=0.25),
    )

    assert report.detection.true_positive == 0
    assert report.detection.false_positive == 0
    assert report.detection.false_negative == 1


def test_duplicate_prediction_is_fatal_input_error() -> None:
    report = evaluate_predictions(
        [truth()], [prediction(), prediction()], config()
    )
    assert [issue.code for issue in report.errors] == [
        "duplicate_prediction_image_id"
    ]
    assert report.evaluated_images == 0


def test_missing_and_unknown_predictions_are_warnings_and_partial_data_runs() -> None:
    report = evaluate_predictions(
        [truth("image-1"), truth("image-2")],
        [prediction("image-1"), prediction("unknown")],
        config(),
    )

    assert report.evaluated_images == 1
    assert report.errors == ()
    assert [(issue.code, issue.image_id) for issue in report.warnings] == [
        ("missing_prediction", "image-2"),
        ("unknown_image_id", "unknown"),
    ]


def test_severity_confusion_and_missing_policy() -> None:
    report = evaluate_predictions(
        [
            truth("wrong", detections=(detection(severity="low"),)),
            truth("missing", detections=(detection(severity="high"),)),
        ],
        [
            prediction(
                "wrong",
                detections=(detection(confidence=0.9, severity="medium"),),
            ),
            prediction(
                "missing",
                detections=(detection(confidence=0.9, severity=None),),
            ),
        ],
        config(),
    )

    assert report.severity.evaluated == 1
    assert report.severity.ignored_missing == 1
    assert report.severity.accuracy == 0
    assert report.severity.confusion_matrix == {"low": {"medium": 1}}
    assert report.severity.labels["low"].false_negative == 1
    assert report.severity.labels["medium"].false_positive == 1


def test_zero_denominators_and_json_round_trip() -> None:
    report = evaluate_predictions(
        [truth(detections=())],
        [prediction(detections=())],
        config(),
    )

    assert report.detection.precision == 0
    assert report.detection.recall == 0
    assert report.detection.f1 == 0
    assert EvaluationReport.from_dict(report.to_dict()) == report


def test_vision_evaluate_cli_writes_atomic_json_report(tmp_path) -> None:
    ground_truth_path = tmp_path / "ground-truth.jsonl"
    predictions_path = tmp_path / "predictions.jsonl"
    report_path = tmp_path / "evaluation-report.json"
    write_jsonl(ground_truth_path, [truth().to_dict()])
    write_jsonl(predictions_path, [prediction().to_dict()])

    code = main(
        [
            "vision-evaluate",
            str(ground_truth_path),
            str(predictions_path),
            str(report_path),
            "--iou-threshold",
            "0.5",
            "--confidence-threshold",
            "0.25",
        ]
    )

    assert code == 0
    value = json.loads(report_path.read_text(encoding="utf-8"))
    assert value["detection"]["f1"] == 1
    assert value["classification"]["space"]["accuracy"] == 1
    assert value["thresholds"] == {"confidence": 0.25, "iou": 0.5}
    assert not list(tmp_path.glob("*.tmp"))


def test_vision_evaluate_cli_returns_nonzero_for_fatal_duplicates(tmp_path) -> None:
    ground_truth_path = tmp_path / "ground-truth.jsonl"
    predictions_path = tmp_path / "predictions.jsonl"
    report_path = tmp_path / "evaluation-report.json"
    write_jsonl(ground_truth_path, [truth().to_dict()])
    write_jsonl(
        predictions_path,
        [prediction().to_dict(), prediction().to_dict()],
    )

    assert (
        main(
            [
                "vision-evaluate",
                str(ground_truth_path),
                str(predictions_path),
                str(report_path),
            ]
        )
        == 1
    )
    assert json.loads(report_path.read_text(encoding="utf-8"))["errors"][0][
        "code"
    ] == "duplicate_prediction_image_id"
