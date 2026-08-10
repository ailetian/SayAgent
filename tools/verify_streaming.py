"""端到端验证：流式是否真正 token-by-token 推送（KB 检索后不应长时间空白）。"""
import json
import time
import urllib.request

BASE = "http://localhost:9095"


def req(method, path, token=None, body=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode())


def main():
    _, login = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})
    token = login.get("token") or (login.get("data") or {}).get("token")
    assert token, "login failed: " + json.dumps(login, ensure_ascii=False)[:200]

    _, agents = req("GET", "/api/agents", token=token)
    data = agents.get("data")
    items = data.get("items") if isinstance(data, dict) else (data or [])
    print("agents:", [(a.get("id"), a.get("name"), "kb=", a.get("knowledgeRefs"), "tools=", a.get("toolRefs")) for a in items])

    # 选带 toolRefs 的验收客服Agent
    target = None
    for a in items:
        if a.get("toolRefs"):
            target = a
            break
    if target is None and items:
        target = items[0]
    print("use agent id=", target["id"], target.get("name"),
          " kb=", target.get("knowledgeRefs"), " tools=", target.get("toolRefs"))

    body = json.dumps({"agentId": str(target["id"]), "message": "知识库里关于年假的规定是什么？请逐步说明。"}).encode()
    r = urllib.request.Request(BASE + "/api/chat/stream", data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)

    first_token_t = None
    last_token_t = None
    token_count = 0
    token_chars = 0
    step_events = []
    gaps = []  # 相邻 token 事件之间的间隔（秒）
    prev_t = None
    kb_done_t = None
    conv_id = None

    t0 = time.time()
    with urllib.request.urlopen(r, timeout=180) as resp:
        for raw in resp:
            line = raw.decode().strip()
            if not line.startswith("data:"):
                continue
            payload = line[len("data:"):].strip()
            try:
                ev = json.loads(payload)
            except Exception:
                continue
            now = time.time() - t0
            etype = ev.get("event")
            if etype == "meta":
                conv_id = ev.get("conversationId")
            elif etype == "step":
                step_events.append((round(now, 2), ev.get("content") or ev.get("label"), ev.get("status") or ev.get("kind")))
                if "知识" in str(ev.get("label", "")) and ev.get("status") in ("done", "ok", "success"):
                    kb_done_t = now
            elif etype == "token":
                token_count += 1
                chunk = ev.get("content") or ev.get("data") or ""
                token_chars += len(chunk)
                if first_token_t is None:
                    first_token_t = now
                if prev_t is not None:
                    gaps.append(now - prev_t)
                prev_t = now
                last_token_t = now
            elif etype == "done":
                break
            elif etype == "error":
                print("ERROR event:", ev)
                break

    print("\n=== 流式验证结果 ===")
    print(f"conversationId : {conv_id}")
    print(f"step 事件数     : {len(step_events)}")
    for s in step_events:
        print(f"  step @{s[0]}s label={s[1]} status={s[2]}")
    print(f"KB检索完成时刻  : {round(kb_done_t,2) if kb_done_t else 'NA'}s")
    print(f"首 token 时刻   : {round(first_token_t,2) if first_token_t else 'NA'}s")
    if kb_done_t and first_token_t:
        print(f"KB完成→首token间隔: {round(first_token_t-kb_done_t,2)}s  (应远小于旧版的'很久')")
    print(f"token 事件总数  : {token_count}")
    print(f"累计字符数      : {token_chars}")
    if gaps:
        print(f"相邻token间隔: min={min(gaps):.3f}s max={max(gaps):.3f}s avg={sum(gaps)/len(gaps):.3f}s")
    print(f"末 token 时刻   : {round(last_token_t,2) if last_token_t else 'NA'}s")

    # 判定
    if token_count >= 3 and (not kb_done_t or (first_token_t - kb_done_t) < 5):
        print("\n结论: ✅ 真正的流式输出（KB 后几乎无空白，token 增量到达）")
    else:
        print("\n结论: ⚠️ 仍有异常，需排查")


if __name__ == "__main__":
    main()
