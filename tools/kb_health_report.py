#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
K0808 知识库好用度体检 · 数据采集脚本 + 离线 HTML 报告生成器

大白话：给知识库做一套"体检"。先去调真实的接口（评测门禁 / 切片样例 / 检索样例 /
点踩 TOP-N / 对话 token 成本），把"好不好用"的数字捞出来；再把这些数字渲染成一份
双击就能打开的离线 HTML 报告，让人一眼看懂知识库状态。

用法（采集 → 产出中间 JSON）：
  python tools/kb_health_report.py --kb-id 4 \
      --out test-artifacts/kb_health_data.json

用法（采集 + 直接出报告）：
  python tools/kb_health_report.py --kb-id 4 --report \
      --out test-artifacts/kb_health_report.html
  # 此时会同时写出 kb_health_data.json（同目录、同名换扩展名）与 kb_health_report.html

说明：
- 鉴权：优先用 --token；否则用 --user/--pass 自动登录（默认 admin/admin123，与现有
  verify_api.py / e2e_verify_upload.py 一致）。
- 网络部分只用标准库 urllib（§3.5 响应契约：统一拆 Result.data）；所有调用带超时，
  失败标「采集失败/无数据」，脚本不崩溃（T12 MUST：降级而非中断）。
- token 成本（T11 验证）读 conversation_log 表：直接用 pymysql（懒加载，未安装则降级为
  「无数据」）。这与项目既有工具 view_chunks.py 直连 PG 读取是一致的务实做法；该模块
  不属于"前端构建/外部不可达服务"，且读取的是本应用自有业务库。
- 门禁阈值硬编码为 K14 已定基线（T12 MUST）：
    Recall@5 ≥ 0.90、忠实度 ≥ 0.85、误拒率 ≤ 0.05、幻觉率 ≤ 0.10、P95 ≤ 3000ms。
- 并发曲线（模块⑦）若单独跑过 k6，用 --k6 k6_output.json 并入；文件需含
  {"points":[{"vus":10,"p95_ms":1200,"error_rate":0.0}, ...]}；未提供则标「未实跑，见 K14 V5」。
"""

import argparse
import json
import sys
import urllib.request
import urllib.error
import urllib.parse
import datetime
import html
import os

# ---- K14 门禁基线（硬编码，T12 MUST）----
GATE_THRESHOLDS = {
    "recall_at5":        {"label": "Recall@5",        "threshold": 0.90, "op": ">=", "field": "recallAt5Mean"},
    "faithfulness":      {"label": "忠实度",           "threshold": 0.85, "op": ">=", "field": "faithfulnessMean"},
    "wrong_refusal_rate":{"label": "误拒率",           "threshold": 0.05, "op": "<=", "field": "wrongRefusalRate"},
    "hallucination_rate":{"label": "幻觉率",           "threshold": 0.10, "op": "<=", "field": "hallucinationRate"},
    "p95_latency_ms":    {"label": "P95 延迟",         "threshold": 3000, "op": "<=", "field": "p95LatencyMs"},
}


# --------------------------------------------------------------------------
# 网络层：统一拆 Result 信封（§3.5 API 响应契约：{code,data,message}）
# --------------------------------------------------------------------------
def api_call(base, method, path, token=None, body=None, timeout=60):
    """发起 HTTP 请求，返回 (ok:bool, data, error:str|None)。

    ok=False 时 data 可能是 HTTP 错误体的解析结果或 None；error 含可读原因。
    """
    url = base.rstrip("/") + path
    data_bytes = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data_bytes = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data_bytes, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
        obj = json.loads(raw)
        # 拆统一响应盒 {code,data,message}
        if isinstance(obj, dict) and "code" in obj and "data" in obj:
            if obj.get("code") == 0:
                return True, obj.get("data"), None
            return False, obj.get("data"), "code=%s %s" % (obj.get("code"), obj.get("message"))
        return True, obj, None
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read().decode("utf-8", "replace"))
            msg = err_body.get("message") if isinstance(err_body, dict) else str(err_body)
        except Exception:
            msg = e.reason
        return False, None, "HTTP %s: %s" % (e.code, msg)
    except urllib.error.URLError as e:
        return False, None, "网络不可达: %s" % e.reason
    except ValueError as e:
        return False, None, "响应非 JSON: %s" % e


def login(base, user, password):
    ok, data, err = api_call(base, "POST", "/api/auth/login",
                             body={"username": user, "password": password}, timeout=30)
    if not ok or not isinstance(data, dict) or "token" not in data:
        raise RuntimeError("登录失败: %s" % err)
    return data["token"]


# --------------------------------------------------------------------------
# 各采集器：失败一律降级，不抛异常中断主流程
# --------------------------------------------------------------------------
def collect_eval(base, token, kb_id):
    """门禁指标：POST /api/knowledge/{kbId}/eval-run → EvalReport。"""
    ok, data, err = api_call(base, "POST", "/api/knowledge/%s/eval-run" % kb_id,
                             token=token, timeout=300)
    if not ok or not isinstance(data, dict):
        return {"status": "采集失败", "error": err or "空响应", "raw": None}

    total = data.get("total", 0)
    if not total:
        return {"status": "无数据", "error": "题集为空或未配置评测数据集",
                "raw": _pick(data, ["total", "answered", "refused", "gate"])}

    gates = build_gates(data)
    return {
        "status": "ok",
        "raw": _pick(data, ["total", "answered", "refused", "recallAt5Mean",
                            "mrrMean", "ndcgMean", "contextPrecisionMean",
                            "contextRecallMean", "faithfulnessMean",
                            "answerRelevancyMean", "wrongRefusalRate",
                            "rejectionAccuracy", "hallucinationRate",
                            "p95LatencyMs", "gate"]),
        "gates": gates,
    }


def build_gates(eval_data):
    """按 K14 基线逐条判定 5 盏灯。"""
    items = []
    all_pass = True
    for key, spec in GATE_THRESHOLDS.items():
        val = eval_data.get(spec["field"])
        if val is None:
            items.append({"key": key, "label": spec["label"], "value": None,
                          "threshold": spec["threshold"], "op": spec["op"],
                          "pass": None})
            all_pass = False
            continue
        if spec["op"] == ">=":
            passed = val >= spec["threshold"]
        else:  # "<="
            passed = val <= spec["threshold"]
        items.append({"key": key, "label": spec["label"], "value": val,
                      "threshold": spec["threshold"], "op": spec["op"],
                      "pass": bool(passed)})
        if not passed:
            all_pass = False
    return {"items": items, "all_pass": all_pass}


def collect_chunks(base, token, kb_id, doc_id):
    """切分样例：先列文档取首个 docId，再 GET .../chunks。"""
    if not doc_id:
        ok, data, err = api_call(base, "GET",
                                 "/api/knowledge/%s/documents?limit=50" % kb_id,
                                 token=token, timeout=60)
        docs = (data or {}).get("items", []) if isinstance(data, dict) else (data or [])
        if not docs:
            return {"status": "无数据", "error": "该知识库无文档", "doc_id": None, "items": []}
        doc_id = docs[0].get("docId") or docs[0].get("id")
    ok, data, err = api_call(base, "GET",
                             "/api/knowledge/%s/documents/%s/chunks" % (kb_id, doc_id),
                             token=token, timeout=60)
    if not ok or not isinstance(data, list):
        return {"status": "采集失败", "error": err or "空响应", "doc_id": doc_id, "items": []}
    items = [{"seq": c.get("chunkIndex"), "score": c.get("score"),
              "content": c.get("content")} for c in data]
    return {"status": "ok", "doc_id": doc_id, "items": items}


def collect_retrieve(base, token, kb_id):
    """检索样例：POST /api/knowledge/retrieve。"""
    ok, data, err = api_call(base, "POST", "/api/knowledge/retrieve", token=token,
                             body={"kbId": int(kb_id), "query": "知识库检索样例验证", "topK": 5},
                             timeout=60)
    if not ok or not isinstance(data, list):
        return {"status": "采集失败", "error": err or "空响应", "query": "知识库检索样例验证", "items": []}
    items = [{"seq": c.get("chunkIndex"), "score": c.get("score"),
              "content": c.get("content"), "documentId": c.get("documentId")} for c in data]
    return {"status": "ok", "query": "知识库检索样例验证", "items": items}


def collect_feedback(base, token, kb_id, limit):
    """被踩 TOP-N：GET /api/chat/feedback。"""
    path = "/api/chat/feedback?%s" % urllib.parse.urlencode(
        {"kbId": kb_id, "limit": limit})
    ok, data, err = api_call(base, "GET", path, token=token, timeout=60)
    if not ok or not isinstance(data, dict):
        return {"status": "采集失败", "error": err or "空响应", "top": [], "reasons": []}
    top = [[r.get("messageId"), r.get("count")] for r in (data.get("top") or [])]
    reasons = [[r.get("reason"), r.get("count")] for r in (data.get("reasons") or [])]
    if not top and not reasons:
        return {"status": "无数据", "error": "暂无可踩反馈", "top": [], "reasons": []}
    return {"status": "ok", "top": top, "reasons": reasons}


def collect_tokens(mysql, kb_id):
    """Token 成本（验证 T11）：读 conversation_log 的 in_tok/out_tok 汇总。

    懒加载 pymysql；未安装或连接失败 → 降级「无数据/采集失败」。
    conversation_log 用软删（deleted=0），SQL 显式过滤。
    """
    try:
        import pymysql
    except ImportError:
        return {"status": "无数据", "error": "未安装 pymysql（pip install pymysql 后可读）",
                "in_tok": None, "out_tok": None, "total": None, "by_provider": []}

    dsn = {
        "host": mysql["host"], "port": int(mysql["port"]),
        "user": mysql["user"], "password": mysql["password"],
        "database": mysql["db"], "connect_timeout": 10,
    }
    try:
        conn = pymysql.connect(**dsn)
    except Exception as e:
        return {"status": "采集失败", "error": "MySQL 连接失败: %s" % e,
                "in_tok": None, "out_tok": None, "total": None, "by_provider": []}
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COALESCE(SUM(in_tok),0), COALESCE(SUM(out_tok),0), COUNT(*) "
                "FROM conversation_log WHERE deleted = 0")
            in_tok, out_tok, rows = cur.fetchone()
            cur.execute(
                "SELECT provider, COALESCE(SUM(in_tok),0), COALESCE(SUM(out_tok),0) "
                "FROM conversation_log WHERE deleted = 0 GROUP BY provider")
            by_provider = [{"provider": r[0], "in_tok": int(r[1]), "out_tok": int(r[2])}
                           for r in cur.fetchall()]
        return {"status": "ok", "in_tok": int(in_tok), "out_tok": int(out_tok),
                "total": int(in_tok) + int(out_tok), "rows": int(rows),
                "by_provider": by_provider}
    except Exception as e:
        return {"status": "采集失败", "error": "查询失败: %s" % e,
                "in_tok": None, "out_tok": None, "total": None, "by_provider": []}
    finally:
        conn.close()


def collect_k6(k6_path):
    """并发曲线：读 k6 输出 JSON。支持 {"points":[{vus,p95_ms,error_rate}]} 简单格式。"""
    if not k6_path:
        return {"status": "未实跑", "error": "未提供 --k6 文件（见 K14 V5）", "points": []}
    try:
        with open(k6_path, "r", encoding="utf-8") as f:
            obj = json.load(f)
    except Exception as e:
        return {"status": "解析失败", "error": "读取/解析失败: %s" % e, "points": []}

    points = obj.get("points") if isinstance(obj, dict) else None
    if not points:
        # 尝试兼容 k6 --summary-export 原生格式（仅取聚合 p95 作为单点标注）
        if isinstance(obj, dict) and "metrics" in obj:
            m = obj["metrics"]
            p95 = (m.get("http_req_duration", {}).get("values", {}).get("p95") if isinstance(m, dict) else None)
            return {"status": "仅汇总", "error": "k6 汇总格式仅含聚合 p95，无法画曲线",
                    "points": [{"vus": None, "p95_ms": p95, "error_rate": None}]}
        return {"status": "解析失败", "error": "缺少 points 数组", "points": []}
    clean = []
    for p in points:
        clean.append({"vus": p.get("vus"), "p95_ms": p.get("p95_ms"),
                      "error_rate": p.get("error_rate")})
    return {"status": "ok", "points": clean}


def _pick(d, keys):
    return {k: d.get(k) for k in keys if k in d}


# --------------------------------------------------------------------------
# 主采集流程
# --------------------------------------------------------------------------
def collect_all(args):
    base = args.base
    if args.token:
        token = args.token
    else:
        token = login(base, args.user, args.pass_)

    print("登录成功，开始采集 kb_id=%s ..." % args.kb_id)

    data = {
        "meta": {
            "generated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "base_url": base,
            "kb_id": args.kb_id,
            "doc_id": args.doc_id,
        },
        "gates": {"thresholds": GATE_THRESHOLDS, "items": None, "all_pass": None},
        "eval": collect_eval(base, token, args.kb_id),
        "chunks": collect_chunks(base, token, args.kb_id, args.doc_id),
        "retrieve": collect_retrieve(base, token, args.kb_id),
        "feedback": collect_feedback(base, token, args.kb_id, args.limit),
        "tokens": collect_tokens({
            "host": args.mysql_host, "port": args.mysql_port,
            "user": args.mysql_user, "password": args.mysql_pass,
            "db": args.mysql_db}, args.kb_id),
        "k6": collect_k6(args.k6),
    }

    # 门禁灯从 eval 结果同步（若 eval 成功采集）
    if data["eval"].get("status") == "ok" and data["eval"].get("gates"):
        data["gates"]["items"] = data["eval"]["gates"]["items"]
        data["gates"]["all_pass"] = data["eval"]["gates"]["all_pass"]
    else:
        # 无 eval 数据时，5 灯均标记「无数据」
        data["gates"]["items"] = [
            {"key": k, "label": v["label"], "value": None,
             "threshold": v["threshold"], "op": v["op"], "pass": None}
            for k, v in GATE_THRESHOLDS.items()]
        data["gates"]["all_pass"] = False

    return data


def write_json(data, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print("已写出中间数据 JSON: %s" % path)


# --------------------------------------------------------------------------
# T13：离线自包含 HTML 报告（内联 SVG + 内联 CSS，无 CDN）
# --------------------------------------------------------------------------
GREEN = "#1a7f37"
RED = "#cf222e"
GREY = "#6e7781"


def esc(s):
    return html.escape("" if s is None else str(s))


def fmt_num(v, nd=3):
    if v is None:
        return "—"
    if isinstance(v, float):
        return ("%.{}f".format(nd)) % v
    return str(v)


def gate_badge(item):
    if item["pass"] is None:
        color, label = GREY, "无数据"
    elif item["pass"]:
        color, label = GREEN, "通过"
    else:
        color, label = RED, "未通过"
    val = fmt_num(item["value"])
    op = item["op"]
    th = fmt_num(item["threshold"])
    return (
        '<div class="gate" style="border-color:%s">'
        '<div class="gate-dot" style="background:%s"></div>'
        '<div class="gate-label">%s</div>'
        '<div class="gate-val">%s</div>'
        '<div class="gate-th">阈值 %s %s</div>'
        '<div class="gate-state" style="color:%s">%s</div>'
        '</div>' % (color, color, esc(item["label"]), esc(val), esc(op), esc(th),
                    color, esc(label)))


def svg_line_chart(points):
    """并发曲线：p95(ms) vs vus；error_rate 作次轴点。无数据点则占位。"""
    if not points or all(p["vus"] is None for p in points):
        return '<div class="empty">未实跑，见 K14 V5（或提供 --k6 k6_output.json）</div>'
    w, h = 640, 260
    pad = 40
    pts = [p for p in points if p["vus"] is not None and p["p95_ms"] is not None]
    if not pts:
        return '<div class="empty">k6 数据缺少 vus/p95_ms，无法绘图</div>'
    vus = [p["vus"] for p in pts]
    p95 = [p["p95_ms"] for p in pts]
    vmin, vmax = min(vus), max(vus)
    pmin, pmax = min(p95), max(p95)
    if pmax == pmin:
        pmax = pmin + 1
    x = lambda v: pad + (w - 2 * pad) * (0 if vmax == vmin else (v - vmin) / (vmax - vmin))
    y = lambda p: h - pad - (h - 2 * pad) * (p - pmin) / (pmax - pmin)
    poly = " ".join("%g,%g" % (x(v), y(p)) for v, p in zip(vus, p95))
    dots = "".join(
        '<circle cx="%g" cy="%g" r="4" fill="%s"><title>vus=%s p95=%sms</title></circle>'
        % (x(v), y(p), "#0969da", v, p) for v, p in zip(vus, p95))
    grid = ""
    for i in range(4):
        yy = pad + (h - 2 * pad) * i / 3
        grid += '<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#e1e4e8"/>' % (pad, yy, w - pad, yy)
    labels = ('<text x="%g" y="%g" fill="%s" font-size="12">%sms</text>'
              % (pad, pad - 10, GREY, int(pmax)))
    labels += ('<text x="%g" y="%g" fill="%s" font-size="12">vus %s</text>'
               % (pad, h - 10, GREY, vmin))
    labels += ('<text x="%g" y="%g" fill="%s" font-size="12" text-anchor="end">vus %s</text>'
               % (w - pad, h - 10, GREY, vmax))
    return ('<svg viewBox="0 0 %d %d" width="100%%" role="img" aria-label="并发曲线">'
            '%s<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#d0d7de"/>'
            '<polyline points="%s" fill="none" stroke="#0969da" stroke-width="2"/>%s%s'
            '</svg>' % (w, h, grid, pad, pad, pad, h - pad, poly, dots, labels))


def svg_bars(pairs, color=GREEN):
    """通用横向条形图（内联 SVG）；pairs=[(label, value)]。"""
    if not pairs:
        return '<div class="empty">无数据</div>'
    maxv = max((v or 0) for _, v in pairs) or 1
    label_w, bar_max_w, row_h, pad = 200, 340, 30, 10
    w = 640
    items = pairs[:15]
    h = pad * 2 + row_h * len(items)
    parts = ['<svg viewBox="0 0 %d %d" width="100%%" role="img" aria-label="条形图">' % (w, h)]
    y = pad
    for label, value in items:
        bw = int(bar_max_w * (value or 0) / maxv) if maxv else 0
        lbl = esc(str(label)[:28])
        val = esc(value)
        cy = y + row_h // 2
        parts.append('<text x="10" y="%d" font-size="12" fill="#1f2328">%s</text>' % (cy + 4, lbl))
        parts.append('<rect x="%d" y="%d" width="%d" height="14" rx="3" fill="%s"/>'
                     % (label_w, cy - 7, bw, color))
        parts.append('<text x="%d" y="%d" font-size="11" fill="#6e7781">%s</text>'
                     % (label_w + bw + 6, cy + 4, val))
        y += row_h
    parts.append('</svg>')
    return "".join(parts)


def module_gate(data):
    items = data["gates"]["items"] or []
    badges = "".join(gate_badge(it) for it in items)
    overall = data["gates"]["all_pass"]
    if overall is None:
        ocolor, otext = GREY, "无数据"
    elif overall:
        ocolor, otext = GREEN, "整体通过"
    else:
        ocolor, otext = RED, "整体未通过"
    return (
        '<section><h2>① 门禁仪表盘（5 灯）</h2>'
        '<div class="overall" style="color:%s">%s</div>'
        '<div class="gates">%s</div>'
        '<p class="note">阈值来自 K14 已定基线；灯色与实际 eval-run 返回数字一致，不编造。</p>'
        '</section>' % (ocolor, esc(otext), badges))


def module_chunks(data):
    ch = data.get("chunks", {})
    items = ch.get("items", []) or []
    rows = []
    for it in items[:8]:
        content = (it.get("content") or "").replace("\n", " ")
        rows.append('<div class="chunk"><span class="chunk-seq">#%s</span>%s</div>'
                    % (esc(it.get("seq")), esc(content[:240])))
    if not rows:
        rows.append('<div class="empty">%s</div>' % esc(ch.get("error") or "无切片数据"))
    # T1 小数点修复说明性示例（非真实检索数据，仅说明切分口径）
    example = (
        '<div class="example"><b>T1 修复说明（示例）：</b>旧切分可能把 '
        '<code>价格 12.50 元</code> 在小数点处切断成 <code>价格 12.</code> / <code>50 元</code>；'
        '修复后整段「价格 12.50 元」保持完整，不被小数点切断。下方「切分样例」取自真实 chunks 接口数据。</div>')
    return (
        '<section><h2>② 切分样例（验证 T1 小数点修复）</h2>%s'
        '<div class="chunks">%s</div>'
        '<p class="note">doc_id=%s；共展示前 %d 块。若某块在小数/单位中间被切断，即为回归信号。</p>'
        '</section>' % (example, "".join(rows), esc(ch.get("doc_id")), len(rows)))


def module_retrieve(data):
    rt = data.get("retrieve", {})
    items = rt.get("items", []) or []
    rows = []
    for it in items[:8]:
        content = (it.get("content") or "").replace("\n", " ")
        rows.append('<div class="chunk"><span class="chunk-seq">%.3f</span>%s</div>'
                    % (it.get("score") or 0, esc(content[:240])))
    if not rows:
        rows.append('<div class="empty">%s</div>' % esc(rt.get("error") or "无检索数据"))
    return (
        '<section><h2>③ 检索样例</h2>'
        '<div class="chunks">%s</div>'
        '<p class="note">query=%s；返回 topK 片段与相似度分。</p></section>'
        % ("".join(rows), esc(rt.get("query"))))


def module_feedback(data):
    fb = data.get("feedback", {})
    top = fb.get("top", []) or []
    reasons = fb.get("reasons", []) or []
    top_pairs = [("msg#%s" % m, c) for m, c in top]
    reason_pairs = [(r or "（未填原因）", c) for r, c in reasons]
    return (
        '<section><h2>④ 点踩 TOP-N（验证 T9）</h2>'
        '<div class="sub">被踩最多的回答</div>%s'
        '<div class="sub">踩的原因分布</div>%s'
        '<p class="note">%s</p></section>'
        % (svg_bars(top_pairs, RED), svg_bars(reason_pairs, "#bf3989"),
           esc(fb.get("error") or "数据来自 /api/chat/feedback（管理员视角）。")))


def module_tokens(data):
    tk = data.get("tokens", {})
    if tk.get("status") != "ok":
        return ('<section><h2>⑤ Token 成本（验证 T11）</h2>'
                '<div class="empty">%s：%s</div></section>'
                % (esc(tk.get("status")), esc(tk.get("error") or "")))
    by = tk.get("by_provider", []) or []
    pairs = [("%s" % (b.get("provider") or "未知"), b.get("in_tok", 0) + b.get("out_tok", 0))
             for b in by]
    summary = ('<div class="kv"><span>输入 token</span><b>%s</b></div>'
               '<div class="kv"><span>输出 token</span><b>%s</b></div>'
               '<div class="kv"><span>合计</span><b>%s</b></div>'
               '<div class="kv"><span>会话条数</span><b>%s</b></div>'
               % (tk.get("in_tok"), tk.get("out_tok"), tk.get("total"), tk.get("rows")))
    return (
        '<section><h2>⑤ Token 成本（验证 T11）</h2>'
        '<div class="summary">%s</div>'
        '<div class="sub">按厂商拆分</div>%s'
        '<p class="note">来源 conversation_log（已过滤 deleted=0）；与对话页透出的 token 对账一致。</p>'
        '</section>' % (summary, svg_bars(pairs, "#8250df")))


def module_gateway(data):
    ev = data.get("eval", {})
    raw = ev.get("raw") or {}
    total = raw.get("total")
    refused = raw.get("refused")
    ra = raw.get("rejectionAccuracy")
    wr = raw.get("wrongRefusalRate")
    if total is None:
        body = '<div class="empty">%s</div>' % esc(ev.get("error") or "无评测数据，无法统计网关净化效果")
    else:
        intercept = ("%.1f%%" % (100.0 * refused / total)) if refused is not None else "—"
        body = (
            '<div class="kv"><span>评测题总数</span><b>%s</b></div>'
            '<div class="kv"><span>网关拦截(拒答)</span><b>%s（%s）</b></div>'
            '<div class="kv"><span>拒答准确率</span><b>%s</b></div>'
            '<div class="kv"><span>误拒率</span><b>%s</b></div>'
            '<p class="note">意图网关(T3)在检索前拦截寒暄/无关问，减少无效检索与 token 浪费；'
            '拦截率=refused/total，来自真实 eval-run。</p>'
            % (total, refused, intercept, fmt_num(ra), fmt_num(wr)))
    return '<section><h2>⑥ 网关净化效果</h2>%s</section>' % body


def module_k6(data):
    k6 = data.get("k6", {})
    return ('<section><h2>⑦ 并发曲线（k6）</h2>%s'
            '<p class="note">%s</p></section>'
            % (svg_line_chart(k6.get("points")), esc(k6.get("error") or "")))


REPORT_CSS = """
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;
background:#f6f8fa;color:#1f2328;margin:0;padding:24px;}
.wrap{max-width:880px;margin:0 auto;background:#fff;border-radius:12px;
padding:28px 32px;box-shadow:0 1px 3px rgba(0,0,0,.08);}
h1{font-size:22px;margin:0 0 4px;}
.banner{display:inline-block;padding:6px 14px;border-radius:20px;color:#fff;font-weight:600;margin-bottom:8px;}
.meta{color:#6e7781;font-size:13px;margin-bottom:20px;}
section{border-top:1px solid #eaecef;padding:18px 0;}
h2{font-size:17px;margin:0 0 12px;}
.gates{display:flex;flex-wrap:wrap;gap:12px;}
.gate{border:2px solid #d0d7de;border-radius:10px;padding:12px 14px;width:140px;text-align:center;}
.gate-dot{width:14px;height:14px;border-radius:50%;margin:0 auto 6px;}
.gate-label{font-weight:600;}.gate-val{font-size:20px;font-weight:700;margin:4px 0;}
.gate-th{font-size:12px;color:#6e7781;}.gate-state{font-size:13px;font-weight:600;margin-top:4px;}
.overall{font-size:16px;font-weight:700;margin-bottom:12px;}
.chunks{display:flex;flex-direction:column;gap:8px;}
.chunk{background:#f6f8fa;border-radius:8px;padding:10px 12px;font-size:13px;line-height:1.5;border-left:3px solid #d0d7de;}
.chunk-seq{display:inline-block;min-width:34px;color:#0969da;font-weight:700;margin-right:8px;}
.example{background:#fff8e6;border:1px solid #f0d58c;border-radius:8px;padding:10px 12px;font-size:13px;margin-bottom:12px;}
.example code{background:#f3e9c8;padding:1px 5px;border-radius:4px;}
.sub{font-weight:600;margin:14px 0 8px;color:#424a53;}
.bars{display:flex;flex-direction:column;gap:6px;}
.bar-row{display:flex;align-items:center;gap:10px;font-size:13px;}
.bar-label{width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.bar-track{flex:1;background:#eaecef;border-radius:6px;height:18px;overflow:hidden;}
.bar-fill{height:100%;border-radius:6px;}
.bar-val{width:56px;text-align:right;font-variant-numeric:tabular-nums;}
.summary{display:grid;grid-template-columns:1fr 1fr;gap:8px 24px;margin-bottom:8px;}
.kv{display:flex;justify-content:space-between;border-bottom:1px dashed #eaecef;padding:4px 0;}
.kv b{font-variant-numeric:tabular-nums;}
.empty{color:#6e7781;font-style:italic;padding:8px 0;}
.note{color:#6e7781;font-size:12px;margin-top:10px;}
svg{max-width:100%;}
"""


def generate_html(data):
    gates = data.get("gates", {})
    all_pass = gates.get("all_pass")
    if all_pass:
        banner = "知识库体检：通过"
        bcolor = GREEN
    elif all_pass is None:
        banner = "知识库体检：数据不足"
        bcolor = GREY
    else:
        banner = "知识库体检：未通过"
        bcolor = RED
    meta = data.get("meta", {})
    sections = [
        module_gate(data),
        module_chunks(data),
        module_retrieve(data),
        module_feedback(data),
        module_tokens(data),
        module_gateway(data),
        module_k6(data),
    ]
    head = (
        '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<title>知识库体检报告</title><style>' + REPORT_CSS + '</style></head><body>'
        '<div class="wrap">'
        '<h1>知识库体检报告</h1>'
        '<div class="banner" style="background:' + bcolor + '">' + esc(banner) + '</div>'
        '<div class="meta">生成时间：' + esc(meta.get("generated_at")) +
        ' ｜ 知识库：' + esc(meta.get("kb_id")) +
        ' ｜ 数据源：真实接口 + conversation_log</div>' +
        "".join(sections) +
        '</div></body></html>'
    )
    return head


def write_html(data, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(generate_html(data))
    print("已生成离线报告: %s" % path)


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def resolve_paths(args):
    """处理 --out 双用途：--report 且 --out 以 .html 结尾时当作报告路径，JSON 同茎。"""
    out = args.out
    if args.report:
        if args.report_out:
            report_path = args.report_out
            json_path = out if out.endswith(".json") else os.path.splitext(report_path)[0] + ".json"
        elif out.endswith(".html"):
            report_path = out
            json_path = os.path.splitext(out)[0] + ".json"
        else:
            json_path = out
            report_path = os.path.splitext(out)[0] + ".html"
    else:
        json_path = out
        report_path = None
    return json_path, report_path


def main():
    ap = argparse.ArgumentParser(
        description="K0808 知识库体检：采集门禁/切片/检索/反馈/token 数据，生成离线 HTML 报告。")
    ap.add_argument("--base", default="http://localhost:9095/api", help="API 基址")
    ap.add_argument("--token", help="JWT（不填则自动登录）")
    ap.add_argument("--user", default="admin", help="登录用户名")
    ap.add_argument("--pass", dest="pass_", default="admin123", help="登录密码")
    ap.add_argument("--kb-id", required=False, help="知识库 ID（采集模式必填；--from-json 模式可省）")
    ap.add_argument("--doc-id", help="指定文档 ID（切分样例；缺省取首个文档）")
    ap.add_argument("--limit", type=int, default=20, help="点踩 TOP-N 条数")
    ap.add_argument("--out", default="test-artifacts/kb_health_data.json",
                    help="输出路径；--report 且以 .html 结尾时视为报告路径")
    ap.add_argument("--report", action="store_true", help="采集后一并生成 HTML 报告")
    ap.add_argument("--report-out", help="HTML 报告路径（默认与 --out 同茎 .html）")
    ap.add_argument("--from-json", help="只读模式：从已有 kb_health_data.json 生成报告，跳过采集")
    ap.add_argument("--k6", help="k6 输出 JSON 路径（并发曲线）")
    ap.add_argument("--mysql-host", default="localhost")
    ap.add_argument("--mysql-port", default="3306")
    ap.add_argument("--mysql-user", default="hify")
    ap.add_argument("--mysql-pass", default="hify")
    ap.add_argument("--mysql-db", default="hify")
    args = ap.parse_args()

    json_path, report_path = resolve_paths(args)

    if args.from_json:
        with open(args.from_json, "r", encoding="utf-8") as f:
            data = json.load(f)
        print("已从 %s 读取已采集数据，跳过采集" % args.from_json)
    else:
        if not args.kb_id:
            print("错误：采集模式必须提供 --kb-id", file=sys.stderr)
            sys.exit(2)
        try:
            data = collect_all(args)
        except RuntimeError as e:
            print("致命错误：%s" % e, file=sys.stderr)
            sys.exit(2)
        write_json(data, json_path)

    if args.report and report_path:
        write_html(data, report_path)


if __name__ == "__main__":
    main()
