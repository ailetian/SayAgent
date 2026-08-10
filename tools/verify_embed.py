import json, urllib.request, time

B = "http://localhost:9095/api"

def req(method, path, token=None, body=None):
    url = B + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {"raw": e.read().decode()[:300]}

# 1. login
s, d = req("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
print("[login] status=%d code=%s" % (s, d.get("code")))
token = d["data"]["token"]

# 2. listBases
s, d = req("GET", "/knowledge/bases", token)
print("[listBases] code=%s count=%s" % (d.get("code"), len(d.get("data", {}).get("items", [])) if d.get("data") else d.get("data")))

# 3. createBase
s, d = req("POST", "/knowledge/bases", token, {"name": "端到端验证KB_embed"})
print("[createBase] code=%s msg=%s kbId=%s" % (d.get("code"), d.get("message"), (d.get("data") or {}).get("id")))
kb_id = (d.get("data") or {}).get("id")

# 4. upload TEXT
s, d = req("POST", "/knowledge/%s/upload" % kb_id, token, [
    {"type": "TEXT", "title": "年假政策说明",
     "content": "年假申请流程：员工需提前在HR系统提交年假申请，填写起止日期，主管审批通过后生效。未休年假可折算工资。"}
])
print("[upload] code=%s msg=%s" % (d.get("code"), d.get("message")))
print("   upload resp:", json.dumps(d.get("data"))[:300])

# 5. poll document status until INDEXED/FAILED
final = None
for i in range(20):
    s, d = req("GET", "/knowledge/%s/documents" % kb_id, token)
    items = (d.get("data") or {}).get("items") or []
    if items:
        st = items[0].get("status") or items[0].get("indexStatus")
        print("  poll#%d docStatus=%s" % (i, st))
        if st in ("INDEXED", "FAILED"):
            final = st
            break
    time.sleep(2)

# 6. ask
s, d = req("POST", "/knowledge/%s/ask" % kb_id, token, {"query": "年假怎么请？"})
print("[ask] code=%s msg=%s" % (d.get("code"), d.get("message")))
ans = (d.get("data") or {})
print("   answer:", json.dumps(ans, ensure_ascii=False)[:500])

# 7. health
s, d = req("GET", "/knowledge/%s/health" % kb_id, token)
print("[health] code=%s msg=%s data=%s" % (d.get("code"), d.get("message"), json.dumps(d.get("data"), ensure_ascii=False)[:200]))
