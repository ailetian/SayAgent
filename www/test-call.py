#!/usr/bin/env python3
"""
最小 MCP Streamable HTTP 客户端（仅用标准库，无第三方依赖）
用来快速验证 PHP MCP 服务器是否可被正常调用。

用法：
  1) 先把 www/ 部署到 Apache（或本地起 php 内置服务器：
     cd www/server-a && php -S localhost:8080
     cd www/server-b && php -S localhost:8081）
  2) 修改下面的 BASE 为你的实际地址
  3) 运行：python3 test-call.py
"""
import json
import urllib.request

# 改成你部署后的实际地址
BASE_A = "http://localhost/server-a/"
BASE_B = "http://localhost/server-b/"


def rpc(base, method, params=None, rid=1):
    body = json.dumps({
        "jsonrpc": "2.0",
        "id": rid,
        "method": method,
        "params": params or {}
    }).encode("utf-8")
    req = urllib.request.Request(base, data=body, headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"
    })
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def test_server(base, label, call_name, call_args):
    print(f"\n===== 测试 {label} ({base}) =====")
    init = rpc(base, "initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "py-test", "version": "1.0"}
    }, 1)
    print("initialize ->", json.dumps(init, ensure_ascii=False))

    lst = rpc(base, "tools/list", {}, 2)
    tools = lst.get("result", {}).get("tools", [])
    print("tools/list ->", [t["name"] for t in tools])

    call = rpc(base, "tools/call", {"name": call_name, "arguments": call_args}, 3)
    print(f"tools/call {call_name} ->", json.dumps(call, ensure_ascii=False))


if __name__ == "__main__":
    test_server(BASE_A, "Server-A 数学", "add", {"a": 2, "b": 3})
    test_server(BASE_B, "Server-B 文本", "reverse", {"text": "MCP"})
