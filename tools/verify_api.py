import json, urllib.request, urllib.error, http.client

BASE = "http://localhost:9095"


def req(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=40) as resp:
            obj = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        obj = json.loads(e.read().decode("utf-8", "replace"))
    # 拆统一响应盒 {code,data,message}
    if isinstance(obj, dict) and "code" in obj and "data" in obj:
        return resp.status if "resp" in dir() else 200, obj["data"]
    return 200, obj


def stream_create(token, body):
    data = json.dumps(body).encode("utf-8")
    r = urllib.request.Request(BASE + "/api/chat/stream", data=data, method="POST")
    r.add_header("Content-Type", "application/json; charset=utf-8")
    r.add_header("Authorization", "Bearer " + token)
    cid = None
    with urllib.request.urlopen(r, timeout=40) as resp:
        buf = b""
        while True:
            try:
                chunk = resp.read(1)
            except http.client.IncompleteRead:
                break
            if not chunk:
                break
            buf += chunk
            while b"\n\n" in buf:
                frame, buf = buf.split(b"\n\n", 1)
                for line in frame.split(b"\n"):
                    line = line.strip()
                    if line.startswith(b"data:"):
                        p = line[5:].strip()
                        if p and p != b"[DONE]":
                            try:
                                ev = json.loads(p)
                                if ev.get("event") == "meta":
                                    cid = ev.get("conversationId")
                            except Exception:
                                pass
    return cid


tok = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})[1]["token"]
print("login ok")

cid = stream_create(tok, {"agentId": "132", "message": "临时测试会话"})
print("temp conv id =", cid)

_, convs = req("GET", "/api/chat", token=tok)
before = next((c for c in convs if c["conversationId"] == cid), None)
print("before:", before)

s, _ = req("PUT", f"/api/chat/{cid}", token=tok, body={"title": "我的重命名测试"})
print("rename status =", s)

s, _ = req("PUT", f"/api/chat/{cid}/pin", token=tok, body={"pinned": True})
print("pin status =", s)

_, convs = req("GET", "/api/chat", token=tok)
after = next((c for c in convs if c["conversationId"] == cid), None)
print("after title =", after["title"], "| pinned =", after["pinned"])
print("置顶后是否排第一:", convs[0]["conversationId"] == cid)

s, _ = req("PUT", f"/api/chat/{cid}/pin", token=tok, body={"pinned": False})
print("unpin status =", s)

s, _ = req("DELETE", f"/api/chat/{cid}", token=tok)
print("delete status =", s)
_, convs = req("GET", "/api/chat", token=tok)
print("delete effective (已消失):", not any(c["conversationId"] == cid for c in convs))
