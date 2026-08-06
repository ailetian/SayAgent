# www/ — 两个 PHP 实现的 MCP 测试服务器

放在 Apache 文档根目录用于验证「MCP 客户端 → HTTP 端点」调用链路。

## 为什么用 PHP
Apache 上跑 PHP 是经典组合，不需要额外的进程管理器：把 `.php` 文件丢进目录就能被直接执行。
Node/Python 在 Apache 下还要配 CGI/WSGI 或另起常驻服务，反而更麻烦。MCP 走 **Streamable HTTP 传输**
（单个 POST 端点，返回 JSON-RPC 2.0），PHP 完全胜任。

## 目录结构
```
www/
├── server-a/           # MCP 服务器 A：数学/计算器工具
│   ├── index.php       # MCP 端点（POST 入口）
│   └── mcp.log         # 运行时访问日志（自动生成，便于排查调用情况）
├── server-b/           # MCP 服务器 B：文本工具
│   ├── index.php
│   └── mcp.log
├── test-call.py        # Python 标准库写的极简测试客户端（无依赖）
└── README.md
```

## 提供的工具
- **Server A（数学）**：`add` / `subtract` / `multiply` / `divide`
- **Server B（文本）**：`greet` / `uppercase` / `reverse` / `word_count` / `current_time`

## 部署到 Apache
1. 把 `www/` 整体复制到 Apache 的文档根目录（如 `htdocs/`、`/var/www/html/` 或虚拟主机根）。
2. 确认 Apache 已启用 PHP（mod_php 或 php-fpm），且允许执行 `.php`。
3. 端点地址即：
   - `http://<你的域名或IP>/server-a/`
   - `http://<你的域名或IP>/server-b/`
4. 确保 `server-a/`、`server-b/` 目录可被 PHP 写入（用于生成 `mcp.log`）；
   若不想写日志，把两个 `index.php` 里的 `log_msg(...)` 调用删掉即可。

## 本地快速自测（不装 Apache）
```bash
cd www/server-a && php -S localhost:8080
cd www/server-b && php -S localhost:8081
python3 www/test-call.py   # 把脚本里的 BASE_A/B 改成 8080/8081 端口
```

## 方式一：curl 手动调
```bash
# 1) 初始化握手
curl -s -X POST http://localhost/server-a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# 2) 列出工具
curl -s -X POST http://localhost/server-a/ \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 3) 调用工具
curl -s -X POST http://localhost/server-a/ \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":3}}}'
```

## 方式二：MCP Inspector（官方可视化调试器）
```bash
npx @modelcontextprotocol/inspector
```
打开后选择 **Transport: Streamable HTTP**，URL 填 `http://<你的域名>/server-a/`，
即可在界面里点选工具、填参数、看返回。

## 方式三：你自己的 MCP 客户端
只要客户端支持 **Streamable HTTP** 传输，把服务器地址填进去即可自动完成
`initialize → tools/list → tools/call` 全流程。本项目后端（LangChain4j / MCP Java SDK）
对接时同样使用这个 URL。

## 排查
- 调用无反应：先看 Apache 错误日志，再看对应 `server-a/mcp.log`、`server-b/mcp.log`（每次请求都会记录原始报文）。
- 返回 405：说明用了 GET，MCP 端点只收 POST。
- 返回 400 Parse error：请求体不是合法 JSON。
- 协议版本不符：服务器支持 `2025-03-26` 与 `2024-11-05`，会自动协商。
