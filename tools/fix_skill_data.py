import json, urllib.request

BASE = "http://localhost:9095"


def req(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r, timeout=30) as resp:
        raw = resp.read()
        return json.loads(raw.decode("utf-8")), raw


# 1) 登录
login, _ = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})
token = login["data"]["token"]
print("login ok")

# 2) 修复已损坏的 id=2（PowerShell 写入时中文变 ?）
fixed, raw = req("PUT", "/api/skills/2", token, {
    "name": "大白话术语",
    "description": "把专业术语翻译成大白话再回答",
    "promptText": "无论用户问什么，先判断其中是否含专业术语；若有，先用一句大白话解释该术语，再正式回答。",
    "enabled": True,
})
print("FIX id=2 ->", fixed["code"], repr(fixed["data"]["name"]), "| 原始字节:", raw)

# 3) 再补一条示例技能，证明浏览器路径（UTF-8）写入正常
created, raw = req("POST", "/api/skills", token, {
    "name": "温和耐心客服",
    "description": "回复保持礼貌与耐心",
    "promptText": "所有回复用礼貌、耐心的语气，先共情理解用户处境，再给可执行的方案；遇到不确定信息明确说明，不编造。",
    "enabled": True,
})
print("CREATE ->", created["code"], "id=", created["data"]["id"], repr(created["data"]["name"]), "| 原始字节:", raw)

# 4) 回读全量，确认中文正确往返
lst, raw = req("GET", "/api/skills", token)
print("\n=== 回读技能库（原始字节已验证为中文）===")
print(raw.decode("utf-8"))
print("\n=== 逐条 name ===")
for s in lst["data"]:
    print("  id=%s name=%s enabled=%s" % (s["id"], s["name"], s["enabled"]))
