from pathlib import Path
import re, base64, json, mimetypes
root = Path(__file__).resolve().parents[1]
index = next((root / "docs").glob("*Index1.0.html"))
s = index.read_text(encoding="utf-8")
hrefs = list(dict.fromkeys(re.findall(r'href="([^"]+)"', s)))
docs = {}
for href in hrefs:
    if href.startswith(("#","http:","https:","mailto:","data:","javascript:")):
        continue
    path = (index.parent / href).resolve()
    if not path.is_file():
        continue
    kind = "html" if path.suffix.lower() in (".html",".htm") else "text"
    raw = path.read_bytes()
    if kind == "html":
        content = raw.decode("utf-8", errors="replace")
        def repl(m):
            attr, value = m.group(1), m.group(2)
            if value.startswith(("#","http:","https:","data:","mailto:")):
                return m.group(0)
            target = (path.parent / value).resolve()
            if not target.is_file():
                return m.group(0)
            mime = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
            return f'{attr}="data:{mime};base64,{base64.b64encode(target.read_bytes()).decode("ascii")}"'
        content = re.sub(r'(src|href)="([^"]+)"', repl, content)
        raw = content.encode("utf-8")
    docs[href] = {"kind": kind, "data": base64.b64encode(raw).decode("ascii")}
for href in docs:
    s = s.replace(f'href="{href}"', f'href="#embedded={href}"')
payload = json.dumps(docs, ensure_ascii=False, separators=(",",":"))
extra = r"""
<style>
#embedOverlay{display:none;position:fixed;inset:0;background:#0b1220d9;z-index:20;padding:18px}
#embedPanel{height:100%;background:#fff;border-radius:14px;display:flex;flex-direction:column;overflow:hidden}
#embedBar{display:flex;justify-content:space-between;align-items:center;padding:10px 14px;background:#123b6d;color:#fff}
#embedClose{border:0;border-radius:8px;padding:6px 12px;cursor:pointer}
#embedFrame{border:0;flex:1;width:100%}#embedText{display:none;flex:1;margin:0;padding:18px;overflow:auto;white-space:pre-wrap;font:13px Consolas,monospace}
</style>
<div id="embedOverlay"><div id="embedPanel"><div id="embedBar"><strong id="embedTitle">Embedded document</strong><button id="embedClose">Close</button></div><iframe id="embedFrame"></iframe><pre id="embedText"></pre></div></div>
<script>
const embeddedDocs = __PAYLOAD__;
const overlay=document.getElementById('embedOverlay'), frame=document.getElementById('embedFrame'), text=document.getElementById('embedText'), title=document.getElementById('embedTitle');
function openEmbedded(key){
 const rec=embeddedDocs[key]; if(!rec){return}
 const bytes=Uint8Array.from(atob(rec.data),c=>c.charCodeAt(0));
 const value=new TextDecoder('utf-8').decode(bytes);
 title.textContent=key; overlay.style.display='block';
 if(rec.kind==='html'){frame.style.display='block';text.style.display='none';frame.srcdoc=value}
 else{frame.style.display='none';text.style.display='block';text.textContent=value}
}
document.addEventListener('click',e=>{const a=e.target.closest('a[href^="#embedded="]');if(a){e.preventDefault();openEmbedded(decodeURIComponent(a.getAttribute('href').slice(11)))}});
document.getElementById('embedClose').onclick=()=>{overlay.style.display='none';frame.srcdoc='';text.textContent=''};
</script>
""".replace("__PAYLOAD__", payload)
s = s.replace("</body>", extra + "</body>")
index.write_text(s, encoding="utf-8")
print(f"embedded={len(docs)} size={index.stat().st_size}")

