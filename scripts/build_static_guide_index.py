"""Build a portable, script-free development guide with static embedded documents."""
from __future__ import annotations

import base64
import html
import mimetypes
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
TARGETS = [
    ("통합 개발 설계서", "AI하자점검_통합개발설계서_1.0.html"),
    ("통합 제품 설계서 v2.0", "AI하자점검_통합제품설계서_v2.0.html"),
    ("AI 분류 어시스턴트 UI/UX 설계검토서", "AI하자점검_AI분류어시스턴트_UIUX_v2.0_설계검토서.html"),
    ("앱–PostgreSQL 연결 진행 보고서 v7.0", "AI하자점검_앱_PostgreSQL_연결진행보고서_v7.0.html"),
    ("무사진 하자 상세 결과 v6.9", "AI하자점검_무사진_상세결과_가상하자마킹_v6.9.html"),
    ("최종 UI/UX 구현 정리서 v3.0", "AI하자점검_최종_UIUX_구현정리서_v3.0.html"),
    ("통합 UI/UX 설계서 v4.2", "AI하자점검_통합_UIUX_설계서_v4.2.html"),
    ("STT·AI 종합의견 UI 재구성 v4.6", "AI하자점검_STT_AI종합의견_UI재구성_v4.6.html"),
    ("실기 화면 UI/UX 검토서 v4.4", "AI하자점검_실기화면_UIUX_검토서_v4.4.html"),
    ("모바일–PostgreSQL 연동 현황·구현 로드맵 v1.0", "AI하자점검_모바일_PostgreSQL_연동현황_구현로드맵_v1.0.html"),
    ("PostgreSQL 실기기 E2E 1단계 구현보고서 v1.1", "AI하자점검_PostgreSQL_실기기E2E_1단계_구현보고서_v1.1.html"),
    ("숫자 비밀번호 인증 변경·재설치 보고서 v1.2", "AI하자점검_숫자비밀번호_인증변경_재설치보고서_v1.2.html"),
]
SOURCES = [
    ("DB 읽기 전용 조사 소스", ROOT / "python/data_engineering/database/vision_inspection.py"),
    ("학습 데이터셋 생성 소스", ROOT / "python/data_engineering/database/dataset.py"),
    ("ConvNeXt 학습 소스", ROOT / "python/vision_ai/pytorch_training.py"),
    ("오프라인 동기화 소스", ROOT.parent / "PinSet-App/android/app/src/main/kotlin/com/axlife/pinset/sync/OfflineSyncManager.kt"),
]
FINAL_UIUX_SCREENS = [
    ("인트로·동호수·평면도", "01-intro.png"),
    ("점검자 로그인", "02-login.png"),
    ("세대 평면도·공간 선택", "04-intro-floorplan.png"),
    ("점검공간 선택 완료", "05-room-selected.png"),
    ("라이브 하자 사진 촬영", "22-live-camera.png"),
    ("하자의견 입력", "12-opinion-top.png"),
    ("의견·AI 추천 상세", "15-detail-top.png"),
    ("저장 후 하자 상세", "14-after-save.png"),
    ("누적 핀 평면도", "18-floorplan.png"),
    ("하자 목록", "19-list.png"),
    ("점검 마감·집계", "20-report.png"),
]

def inline_images(content: str, document: Path) -> str:
    def replace(match: re.Match[str]) -> str:
        value = match.group(1)
        if value.startswith(("#", "data:", "http:", "https:")):
            return match.group(0)
        asset = (document.parent / value).resolve()
        if not asset.is_file():
            return match.group(0)
        mime = mimetypes.guess_type(asset.name)[0] or "application/octet-stream"
        encoded = base64.b64encode(asset.read_bytes()).decode("ascii")
        return f'src="data:{mime};base64,{encoded}"'
    return re.sub(r'src="([^"]+)"', replace, content)

def body_html(document: Path) -> str:
    source = document.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"<body[^>]*>([\s\S]*?)</body>", source, re.I)
    body = match.group(1) if match else source
    body = re.sub(r"<script[\s\S]*?</script>", "", body, flags=re.I)
    body = inline_images(body, document)
    body = re.sub(r'\s(?:href)="(?!#|data:|https?:)[^"]+"', ' href="#"', body)
    return body

cards = []
sections = []
for index, (title, name) in enumerate(TARGETS, 1):
    path = DOCS / name
    if not path.is_file():
        continue
    anchor = f"doc-{index}"
    cards.append(f'<a class="card" href="#{anchor}"><b>내장 문서 {index}</b><h3>{html.escape(title)}</h3><p>이 파일 안에 본문과 필요한 이미지를 정적으로 포함</p></a>')
    sections.append(f'<details class="embedded" id="{anchor}"><summary>{index}. {html.escape(title)} <span>클릭하여 열기</span></summary><article>{body_html(path)}</article></details>')

source_sections = []
for index, (title, path) in enumerate(SOURCES, 1):
    if path.is_file():
        text = html.escape(path.read_text(encoding="utf-8", errors="replace"))
        source_sections.append(f'<details class="embedded"><summary>소스 {index}. {html.escape(title)} <span>클릭하여 열기</span></summary><article><pre>{text}</pre></article></details>')

screen_dir = DOCS / "assets" / "uiux-v4.4-device"
screen_cards = []
for title, filename in FINAL_UIUX_SCREENS:
    image = screen_dir / filename
    if image.is_file():
        encoded = base64.b64encode(image.read_bytes()).decode("ascii")
        screen_cards.append(f'<figure><img src="data:image/png;base64,{encoded}" alt="{html.escape(title)}"><figcaption>{html.escape(title)}</figcaption></figure>')

page = f"""<!doctype html>
<html lang="ko"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AI하자점검 개발가이드 Index1.8</title>
<style>
:root{{--bg:#f4f7fb;--card:#fff;--ink:#172033;--muted:#64748b;--line:#dbe3ef;--blue:#155eef;--soft:#eaf1ff}}*{{box-sizing:border-box}}html{{scroll-behavior:smooth}}body{{margin:0;background:var(--bg);color:var(--ink);font-family:"Malgun Gothic","Noto Sans KR",Arial,sans-serif;line-height:1.6}}.wrap{{max-width:1180px;margin:auto;padding:24px}}.hero{{background:linear-gradient(135deg,#123b6d,#155eef);color:#fff;border-radius:18px;padding:34px 30px;margin-bottom:20px}}.hero h1{{margin:0 0 8px;font-size:30px}}.hero p{{margin:0;opacity:.92}}.pill{{display:inline-block;margin:16px 6px 0 0;padding:4px 10px;border-radius:999px;background:#ffffff25;font-size:13px}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px}}.card{{display:block;background:#fff;border:1px solid var(--line);border-radius:14px;padding:18px;color:inherit;text-decoration:none}}.card:hover{{border-color:var(--blue);box-shadow:0 5px 18px #155eef18}}.card b{{color:var(--blue);font-size:12px}}.card h3{{margin:5px 0;font-size:17px}}.card p{{margin:0;color:var(--muted);font-size:14px}}h2{{margin:30px 0 12px;color:#123b6d;border-bottom:2px solid var(--line);padding-bottom:6px}}.panel,.embedded{{background:#fff;border:1px solid var(--line);border-radius:14px;padding:18px;margin:0 0 14px}}.embedded{{padding:0;overflow:hidden}}.embedded summary{{cursor:pointer;padding:16px 18px;font-size:18px;font-weight:700;color:#123b6d;background:#f8fbff}}.embedded summary span{{float:right;font-size:13px;font-weight:400;color:var(--muted)}}.embedded article{{padding:22px;overflow:auto;max-width:100%}}.embedded article img{{max-width:100%;height:auto}}.embedded article table{{max-width:100%;border-collapse:collapse}}.embedded article td,.embedded article th{{border:1px solid #d6deea;padding:8px}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;background:#111827;color:#e5e7eb;padding:14px;border-radius:10px}}code{{background:var(--soft);padding:2px 5px;border-radius:4px}}@media(max-width:760px){{.wrap{{padding:14px}}.hero h1{{font-size:24px}}.embedded article{{padding:14px}}.embedded summary span{{display:none}}}}
.screen-grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:16px}}.screen-grid figure{{margin:0;background:#f8fbff;border:1px solid var(--line);border-radius:12px;padding:10px}}.screen-grid img{{display:block;width:100%;height:auto;border-radius:8px}}.screen-grid figcaption{{padding:8px 3px 2px;color:#123b6d;font-weight:700;font-size:14px}}
</style></head><body><div class="wrap">
<header class="hero"><small>Apartment Defect AI · 단일 파일 배포본</small><h1>AI하자점검 개발가이드 Index1.8</h1><p>세부 문서와 이미지가 이 HTML 안에 정적으로 포함되어 있습니다. 인터넷·외부 파일·JavaScript 없이 열립니다.</p><span class="pill">UTF-8</span><span class="pill">정적 임베디드</span><span class="pill">2026-07-27</span></header>
<section><h2>개발환경</h2><div class="panel">Windows PowerShell · Apartment_Defect_AI · PinSet-App · Python .venv · PostgreSQL 18 · FastAPI · JDK 17 · Galaxy S25 Ultra · Google Colab ConvNeXt-Tiny</div></section>
<section id="final-uiux"><h2>최종 UI/UX 실제 화면 구성안</h2><div class="panel"><p>현재 구현본에서 캡처한 실제 Android 화면입니다. 아래 이미지는 이 HTML 안에 포함되어 있어 별도 파일 없이 표시됩니다.</p><div class="screen-grid">{''.join(screen_cards)}</div></div></section>
<section><h2>내장 문서 목록</h2><div class="grid">{''.join(cards)}</div></section>
<section><h2>세부 문서</h2><p>각 제목을 누르면 같은 파일 안에서 본문이 펼쳐집니다.</p>{''.join(sections)}</section>
<section><h2>주요 구현 소스</h2>{''.join(source_sections)}</section>
<section><h2>운영 원칙</h2><div class="panel"><ul><li>운영 AWS RDS에는 검증 목적의 UPDATE·INSERT·DELETE를 실행하지 않습니다.</li><li>현장 사진과 의견은 폰 로컬에 저장한 후 통신 회복 시 서버로 전송합니다.</li><li>입주민 원문 의견은 보존하고, AI 표준화 의견과 분리합니다.</li><li>DB 비밀번호·DSN·접속 문자열은 문서에 기록하지 않습니다.</li></ul></div></section>
</div></body></html>"""

for name in ("AI하자점검_개발가이드_Index1.8.html", "AI하자점검_개발가이드_Index1.0.html"):
    (DOCS / name).write_text(page, encoding="utf-8")
print("static embedded documents:", len(sections), "sources:", len(source_sections))
