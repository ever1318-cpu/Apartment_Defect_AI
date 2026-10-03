from pathlib import Path
import re, json
docs_dir = Path(__file__).resolve().parents[1] / "docs"
for index in docs_dir.glob("*개발가이드_Index1.*.html"):
    s = index.read_text(encoding="utf-8")
    match = re.search(r"const embeddedDocs = (\{.*?\});", s, re.S)
    if not match:
        continue
    docs = json.loads(match.group(1))
    for key, rec in docs.items():
        mime = "text/html" if rec["kind"] == "html" else "text/plain"
        uri = "data:" + mime + ";base64," + rec["data"]
        s = s.replace(f'href="#embedded={key}"', f'href="{uri}" target="_blank"')
    index.write_text(s, encoding="utf-8")
    print(index.name, len(docs), index.stat().st_size)

