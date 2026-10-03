"""Generate a standalone HTML utility report from a completed training run."""

from __future__ import annotations

import html
import json
from pathlib import Path
from typing import Any, Mapping


def write_training_report(
    run_directory: str | Path, output: str | Path
) -> Path:
    run = Path(run_directory)
    manifest = _read(run / "run_manifest.json")
    metrics = _read(run / "final_metrics.json")
    rows = []
    for task in ("area", "part", "part_detail", "work_kind", "cause"):
        accuracy = float(metrics.get(f"test_{task}_accuracy", 0))
        macro_f1 = float(metrics.get(f"test_{task}_macro_f1", 0))
        top3 = float(metrics.get(f"test_{task}_top3_accuracy", 0))
        rows.append(
            f"<tr><td>{html.escape(task)}</td><td>{accuracy:.1%}</td>"
            f"<td>{macro_f1:.1%}</td><td>{top3:.1%}</td>"
            f"<td>{_grade(task, accuracy, macro_f1, top3)}</td></tr>"
        )
    document = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>하자 AI 학습 평가 리포트</title>
<style>
body{{margin:0;background:#f4f7fb;color:#172033;font:15px/1.65 "Malgun Gothic",sans-serif}}
main{{max-width:1000px;margin:28px auto;padding:0 18px}}header,section{{background:white;border:1px solid #dce4ee;border-radius:16px;padding:32px;margin-bottom:16px}}
header{{background:linear-gradient(135deg,#102a56,#175cd3);color:white}}h1{{margin:0 0 8px}}table{{width:100%;border-collapse:collapse}}th,td{{padding:12px;border-bottom:1px solid #dce4ee;text-align:left}}th{{background:#f8fafc}}
.warn{{padding:16px;background:#fffaeb;border-left:5px solid #b54708}}code{{background:#eef4ff;padding:2px 6px;border-radius:4px}}
</style></head><body><main>
<header><h1>하자 AI 학습 평가 리포트</h1>
<p>Run {html.escape(str(manifest.get("run_id", "unknown")))}</p></header>
<section><h2>실행 결과</h2><p>상태: <strong>{html.escape(str(manifest.get("status")))}</strong><br>
데이터셋: <code>{html.escape(str(manifest.get("dataset_version")))}</code><br>
최적 epoch: {float(metrics.get("best_epoch", 0)):.0f}<br>
Validation accuracy: {float(metrics.get("best_validation_accuracy", 0)):.1%}</p></section>
<section><h2>Test 지표</h2><table><thead><tr><th>태스크</th><th>Accuracy</th>
<th>Macro F1</th><th>Top-3</th><th>판정</th></tr></thead><tbody>
{''.join(rows)}</tbody></table></section>
<section><h2>해석 주의사항</h2><div class="warn">파일럿 데이터는 파이프라인 검증용이다.
최종 효용성은 충분한 클래스별 표본, 중복 제거, 미학습 현장 test 세트를 사용해 다시 판단해야 한다.</div></section>
</main></body></html>"""
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(document, encoding="utf-8")
    return destination


def write_comparison_report(
    original_run: str | Path,
    cleaned_run: str | Path,
    output: str | Path,
) -> Path:
    original = _read(Path(original_run) / "final_metrics.json")
    cleaned = _read(Path(cleaned_run) / "final_metrics.json")
    rows = []
    for task in ("area", "part", "part_detail", "work_kind", "cause"):
        cells = [html.escape(task)]
        for metric in ("accuracy", "macro_f1", "top3_accuracy"):
            before = float(original.get(f"test_{task}_{metric}", 0))
            after = float(cleaned.get(f"test_{task}_{metric}", 0))
            cells.extend((f"{before:.1%}", f"{after:.1%}", f"{after-before:+.1%}"))
        rows.append("<tr>" + "".join(f"<td>{cell}</td>" for cell in cells) + "</tr>")
    document = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>원본·정제본 ConvNeXt 비교</title><style>
body{{font:14px/1.6 "Malgun Gothic",sans-serif;background:#f4f7fb;color:#172033}}
main{{max-width:1200px;margin:24px auto}}section{{background:white;padding:28px;border-radius:14px}}
table{{width:100%;border-collapse:collapse}}th,td{{padding:10px;border:1px solid #dce4ee}}
th{{background:#eef4ff}}.warn{{background:#fffaeb;padding:16px;border-left:5px solid #b54708}}
</style></head><body><main><section><h1>원본·정제본 ConvNeXt-Tiny 비교</h1>
<div class="warn">파일럿 표본 결과이며 정제 효과의 최종 판정이 아니다. 전체 데이터 및
오염 유형별 고정 test 세트에서 재평가해야 한다.</div>
<table><thead><tr><th rowspan="2">태스크</th><th colspan="3">Accuracy</th>
<th colspan="3">Macro F1</th><th colspan="3">Top-3</th></tr>
<tr><th>원본</th><th>정제+일관성</th><th>차이</th><th>원본</th><th>정제+일관성</th>
<th>차이</th><th>원본</th><th>정제+일관성</th><th>차이</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table></section></main></body></html>"""
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(document, encoding="utf-8")
    return destination


def _read(path: Path) -> Mapping[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def _grade(task: str, accuracy: float, macro_f1: float, top3: float) -> str:
    targets = {
        "part": (0.90, 0.80),
        "part_detail": (0.70, 0.85),
        "work_kind": (0.70, 0.90),
        "area": (0.80, 0.85),
        "cause": (0.75, 0.85),
    }
    primary, top_target = targets[task]
    return "목표 충족" if macro_f1 >= primary and top3 >= top_target else "개선 필요"
