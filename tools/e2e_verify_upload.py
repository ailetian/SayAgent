import urllib.request, urllib.error, json, os, uuid, time, sys

BASE = "http://localhost:9095/api"
ART = r"F:\AI探索实验室\01_Playground\hify_python\test-artifacts"
PDF = os.path.join(ART, "sample_test.pdf")
DOCX = os.path.join(ART, "sample_test.docx")

def req(method, url, token=None, body=None, raw=False):
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None and not raw:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    r = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

def jget(text):
    return json.loads(text)

# 1) login
st, tx = req("POST", BASE + "/auth/login", body={"username": "admin", "password": "admin123"})
print("LOGIN", st)
tok = jget(tx)["data"]["token"]

# 2) create base
st, bx = req("POST", BASE + "/knowledge/bases", token=tok, body={"name": "E2E_PDF_DOCX_verify"})
print("CREATE_BASE", st, bx[:200])
bdata = jget(bx)["data"]
kb_id = bdata.get("id") or bdata.get("kbId")
print("KB_ID =", kb_id)

# 3) upload multipart
def post_multipart(url, token, paths, field="files"):
    boundary = "----hify" + uuid.uuid4().hex
    body = b""
    for p in paths:
        fn = os.path.basename(p)
        with open(p, "rb") as f:
            data = f.read()
        body += ("--" + boundary + "\r\n").encode()
        body += ('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (field, fn)).encode("utf-8")
        body += b"Content-Type: application/octet-stream\r\n\r\n"
        body += data + b"\r\n"
    body += ("--" + boundary + "--\r\n").encode()
    headers = {"Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary}
    r = urllib.request.Request(url, data=body, method="POST", headers=headers)
    with urllib.request.urlopen(r, timeout=120) as resp:
        return resp.status, resp.read().decode("utf-8")

st, ux = post_multipart(BASE + f"/knowledge/{kb_id}/upload-files", tok, [PDF, DOCX])
print("UPLOAD", st, ux[:400])
items = jget(ux)["data"]["items"]
print("UPLOADED items:", [(i["docId"], i["status"]) for i in items])

# 4) poll status via documents list
doc_ids = [i["docId"] for i in items]
final = {}
deadline = time.time() + 90
while time.time() < deadline:
    st, lx = req("GET", BASE + f"/knowledge/{kb_id}/documents?limit=50", token=tok)
    docs = jget(lx)["data"]["items"]
    by_id = {d["docId"]: d for d in docs}
    done = True
    for did in doc_ids:
        d = by_id.get(did, {})
        final[did] = (d.get("status"), d.get("chunkCount"))
        if d.get("status") not in ("INDEXED", "FAILED"):
            done = False
    print("POLL", [(did, final[did]) for did in doc_ids])
    if done:
        break
    time.sleep(3)

# 5) retrieval check on the INDEXED one(s)
for did, (status, chunks) in final.items():
    if status == "INDEXED":
        st, rx = req("POST", BASE + f"/knowledge/retrieve", token=tok,
                     body={"kbId": kb_id, "query": "Hify knowledge base RAG pipeline", "topK": 3})
        rd = jget(rx)["data"]
        print(f"RETRIEVE doc={did} chunks={chunks} hits={len(rd)}")

print("RESULT", json.dumps(final, ensure_ascii=False))
