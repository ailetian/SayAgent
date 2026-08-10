"""验证 message.trace_json 是否落库（KB/MCP 调用轨迹）。复用 verify_api.py 的鉴权方式。"""
import json
import urllib.request

BASE = "http://localhost:9095"


def req(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r, timeout=120) as resp:
        return resp.status, json.loads(resp.read().decode())


def stream_chat(token, agent_id, message):
    body = json.dumps({"agentId": str(agent_id), "message": message}).encode()
    r = urllib.request.Request(BASE + "/api/chat/stream", data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    conv_id = None
    with urllib.request.urlopen(r, timeout=120) as resp:
        for raw in resp:
            line = raw.decode().strip()
            if not line.startswith("data:"):
                continue
            payload = line[len("data:"):].strip()
            try:
                ev = json.loads(payload)
            except Exception:
                continue
            if ev.get("event") == "meta":
                conv_id = ev.get("conversationId")
            if ev.get("event") == "done":
                break
    return conv_id


def main():
    _, login = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})
    print("login resp:", json.dumps(login, ensure_ascii=False)[:300])
    token = login.get("token") or (login.get("data") or {}).get("token")
    print("login ok, token?", bool(token))

    _, agents = req("GET", "/api/agents", token=token)
    items = agents.get("data") if isinstance(agents, dict) else agents
    items = items or []
    print("agents:", [(a.get("id"), a.get("name"), a.get("knowledgeRefs"), a.get("toolRefs")) for a in items])

    target = None
    for a in items:
        if a.get("knowledgeRefs") or a.get("toolRefs"):
            target = a
            break
    if target is None and items:
        target = items[0]
    if target is None:
        print("NO AGENT")
        return
    print("use agent:", target.get("id"), target.get("name"),
          "kb=", target.get("knowledgeRefs"), "tools=", target.get("toolRefs"))

    conv_id = stream_chat(token, target["id"], "知识库里关于年假的规定是什么？")
    print("convId:", conv_id)
    if not conv_id:
        return

    _, page = req("GET", f"/api/chat/{conv_id}/messages", token=token)
    msgs = page.get("data", {}).get("items") if isinstance(page.get("data"), dict) else page.get("items")
    msgs = msgs or []
    for m in msgs:
        if m.get("role") in ("ASSISTANT", "assistant"):
            print("ASSISTANT traceJson:", m.get("traceJson"))
            print("content head:", (m.get("content") or "")[:80])
    # 清理：删掉测试会话
    req("DELETE", f"/api/chat/{conv_id}", token=token)
    print("cleaned test conversation")


if __name__ == "__main__":
    main()
