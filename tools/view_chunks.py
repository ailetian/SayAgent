"""
查看向量库（PostgreSQL hify_vector）里「切片后的内容」。

切片存在 PG 的 document_chunk 表：
  document_id TEXT, kb_id BIGINT, seq INT, content TEXT, embedding vector(1024), created_at TIMESTAMP
（与后端 V2__knowledge_chunk.sql 一致；向量列走 HNSW 索引检索，这里只做「查看内容」。）

用法：
  python view_chunks.py                 # 列出全部切片（默认前 50 条）
  python view_chunks.py --kb 4          # 只看某个知识库(kb_id)的切片
  python view_chunks.py --doc DOC123    # 只看某篇文档的切片
  python view_chunks.py --limit 200     # 增加条数
  python view_chunks.py --full          # 显示完整 content（默认截断前 120 字）

依赖：psycopg2-binary（pip install psycopg2-binary）
"""
import argparse
import sys

try:
    import psycopg2
except ImportError:
    print("缺少依赖：请先 pip install psycopg2-binary")
    sys.exit(1)

DSN = "host=localhost port=5432 dbname=hify_vector user=hify password=hify"

SQL = """
SELECT document_id, kb_id, seq, content, length(content) AS content_len, created_at
FROM document_chunk
{where}
ORDER BY kb_id, document_id, seq
LIMIT %(limit)s
"""

COUNT_SQL = "SELECT kb_id, count(*) AS chunks, count(distinct document_id) AS docs FROM document_chunk {where} GROUP BY kb_id ORDER BY kb_id"


def build_where(args):
    conds = []
    params = {}
    if args.kb is not None:
        conds.append("kb_id = %(kb)s")
        params["kb"] = args.kb
    if args.doc:
        conds.append("document_id = %(doc)s")
        params["doc"] = args.doc
    where = ("WHERE " + " AND ".join(conds)) if conds else ""
    return where, params


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kb", type=int, help="按知识库 id 过滤")
    ap.add_argument("--doc", help="按文档业务 id 过滤")
    ap.add_argument("--limit", type=int, default=50)
    ap.add_argument("--full", action="store_true", help="显示完整 content（默认截断）")
    args = ap.parse_args()

    where, params = build_where(args)
    params["limit"] = args.limit

    conn = psycopg2.connect(DSN)
    try:
        cur = conn.cursor()
        # 概览：每个 kb 的切片数 / 文档数
        cur.execute(COUNT_SQL.format(where=where), params)
        print("=== 概览（按知识库）===")
        for kb_id, chunks, docs in cur.fetchall():
            print(f"  kb_id={kb_id}  切片数={chunks}  文档数={docs}")
        print()

        cur.execute(SQL.format(where=where), params)
        rows = cur.fetchall()
        print(f"=== 切片明细（最多 {args.limit} 条）===  共 {len(rows)} 条")
        for doc_id, kb_id, seq, content, clen, created in rows:
            text = content if args.full else (content[:120] + ("…" if len(content) > 120 else ""))
            text = text.replace("\n", "⏎")
            print(f"[{kb_id}] {doc_id} #{seq}  (len={clen})")
            print(f"    {text}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
