import sys
from pathlib import Path

import pytest

from vision_ai.backend_registry import BackendRegistry, build_default_registry
from vision_ai.backends import ReferenceVisionBackend, create_backend
from vision_ai.onnx_backend import OnnxVisionBackend, create_onnx_session


class FakeInput:
    name = "images"


class FakeSession:
    def __init__(self, outputs=None):
        self.outputs = outputs or valid_outputs()
        self.calls = []

    def get_inputs(self):
        return [FakeInput()]

    def run(self, output_names, input_feed):
        self.calls.append((tuple(output_names), input_feed))
        return self.outputs


def valid_outputs():
    return [
        [[0.9]],
        [[0.1, 0.8, 0.1]],
        [[0.7, 0.2, 0.1]],
        [[0.1, 0.2, 0.7]],
        [[[0.1, 0.1, 0.4, 0.4]]],
        [[0.95]],
        [[0]],
    ]


def create_model(path: Path) -> Path:
    path.write_bytes(b"fake-onnx-model")
    return path


def test_registry_creates_reference_and_rejects_unknown_backend() -> None:
    registry = build_default_registry()
    assert registry.names() == ("onnx", "reference")
    assert isinstance(registry.create("reference"), ReferenceVisionBackend)
    with pytest.raises(ValueError, match="unknown backend"):
        registry.create("missing")


def test_registry_rejects_duplicate_backend_name() -> None:
    registry = BackendRegistry()
    registry.register("reference", ReferenceVisionBackend)
    with pytest.raises(ValueError, match="already registered"):
        registry.register("REFERENCE", ReferenceVisionBackend)


def test_onnx_backend_validates_model_before_creating_session(tmp_path) -> None:
    called = False

    def factory(model_path, providers):
        nonlocal called
        called = True
        return FakeSession()

    with pytest.raises(FileNotFoundError, match="model file does not exist"):
        OnnxVisionBackend(tmp_path / "missing.onnx", session_factory=factory)
    assert called is False


def test_onnxruntime_is_optional_until_session_creation(
    tmp_path, monkeypatch
) -> None:
    model = create_model(tmp_path / "model.onnx")
    monkeypatch.setitem(sys.modules, "onnxruntime", None)
    with pytest.raises(RuntimeError, match="optional dependencies"):
        create_onnx_session(model)


def test_onnx_session_creation_is_separate_and_inference_is_cached(tmp_path) -> None:
    model = create_model(tmp_path / "model.onnx")
    session = FakeSession()
    factory_calls = []

    def factory(model_path, providers):
        factory_calls.append((model_path, providers))
        return session

    backend = OnnxVisionBackend(
        model,
        model_version="defect-7",
        providers=("CPUExecutionProvider",),
        session_factory=factory,
        input_loader=lambda path: f"tensor:{path}",
    )

    assert backend.assess_quality("image.png").acceptable
    assert backend.classify("image.png", "space")[1].label == "kitchen"
    detection = backend.detect("image.png")[0]

    assert detection.label == "crack"
    assert detection.box.x_max == pytest.approx(0.4)
    assert factory_calls == [(model.resolve(), ("CPUExecutionProvider",))]
    assert len(session.calls) == 1
    assert session.calls[0][1] == {"images": "tensor:image.png"}


def test_registry_creates_onnx_backend_with_fake_session(tmp_path) -> None:
    model = create_model(tmp_path / "model.onnx")
    backend = create_backend(
        "onnx",
        model_path=model,
        session_factory=lambda path, providers: FakeSession(),
        input_loader=lambda path: "tensor",
    )
    assert isinstance(backend, OnnxVisionBackend)


@pytest.mark.parametrize(
    ("output_index", "invalid_output", "message"),
    [
        (0, [0.9], "batch shape"),
        (1, [[0.1, 0.9]], "shape"),
        (4, [[[0.1, 0.2, 0.3]]], "shape"),
    ],
)
def test_onnx_backend_rejects_invalid_output_shapes(
    tmp_path, output_index, invalid_output, message
) -> None:
    outputs = valid_outputs()
    outputs[output_index] = invalid_output
    backend = OnnxVisionBackend(
        create_model(tmp_path / "model.onnx"),
        session_factory=lambda path, providers: FakeSession(outputs),
        input_loader=lambda path: "tensor",
    )

    with pytest.raises(ValueError, match=message):
        if output_index == 0:
            backend.assess_quality("image.png")
        elif output_index == 1:
            backend.classify("image.png", "space")
        else:
            backend.detect("image.png")
