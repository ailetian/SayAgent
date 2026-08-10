import urllib.request, urllib.error, json, time

BASE = "http://localhost:9095"

def req(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return e.code, {}

# 1) login
st, login = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})
assert st == 200, login
tok = login["data"]["token"]
print("LOGIN ok")

# 2) list bases, find the company KB
st, kbj = req("GET", "/api/knowledge/bases?limit=50", token=tok)
bases_raw = kbj.get("data")
bases = bases_raw.get("items") if isinstance(bases_raw, dict) else (bases_raw or [])
print("BASES:", json.dumps([{"id": b.get("id"), "name": b.get("name")} for b in bases], ensure_ascii=False))
kb = next((b for b in bases if "公司" in (b.get("name") or "")), None)
if kb is None and bases:
    kb = bases[0]
kb_id = kb["id"]
print("TARGET KB:", kb_id, kb.get("name"))

# 3) list agents, find one referencing kb_id
st, aj = req("GET", "/api/agents", token=tok)
agents = aj.get("data") or []
print("AGENTS:", json.dumps([{"id": a.get("id"), "name": a.get("name"), "kbRefs": a.get("knowledgeRefs")} for a in agents], ensure_ascii=False))
agent = next((a for a in agents if kb_id in (a.get("knowledgeRefs") or [])), None)
if agent is None:
    agent = next((a for a in agents if (a.get("knowledgeRefs") or [])), None)
agent_id = agent["id"]
print("TARGET AGENT:", agent_id, agent.get("name"), "kbRefs=", agent.get("knowledgeRefs"))

# 4) chat via SSE
question = "公司的全称是什么"
data = json.dumps({"agentId": str(agent_id), "message": question}).encode("utf-8")
r = urllib.request.Request(BASE + "/api/chat/stream", data=data, method="POST")
r.add_header("Content-Type", "application/json; charset=utf-8")
r.add_header("Authorization", "Bearer " + tok)
cid = None
answer_parts = []
retrieval_step = None
with urllib.request.urlopen(r, timeout=90) as resp:
    buf = b""
    while True:
        try:
            chunk = resp.read(1)
        except Exception:
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
                        except Exception:
                            continue
                        et = ev.get("event")
                        if et == "meta":
                            cid = ev.get("conversationId")
                        elif et == "token":
                            answer_parts.append(ev.get("content") or "")
                        elif et == "step" and ev.get("kind") == "retrieval":
                            retrieval_step = ev.get("content")
                        elif et == "done":
                            print("DONE inTok/outTok:", ev.get("inTokens"), ev.get("outTokens"))

print("CONV_ID:", cid)
print("RETRIEVAL_STEP:", retrieval_step)
answer = "".join(answer_parts)
print("ANSWER:", answer[:600])

# 5) read message trace for retrieval scores
if cid:
    st, mj = req("GET", f"/api/chat/{cid}/messages", token=tok)
    data = mj.get("data")
    msgs = data.get("items") if isinstance(data, dict) else (data or [])
    print("MSGS raw shape:", type(data).__name__, "| count:", len(msgs) if isinstance(msgs, list) else "n/a")
    for m in (msgs if isinstance(msgs, list) else []):
        if isinstance(m, dict):
            print("MSG keys:", list(m.keys()), "| role=", m.get("role"), "| status=", m.get("status"))
            if m.get("role") in ("assistant", "ASSISTANT"):
                tj = m.get("traceJson") or m.get("trace_json")
                print("ASSISTANT traceJson:", json.dumps(tj, ensure_ascii=False)[:1500])
