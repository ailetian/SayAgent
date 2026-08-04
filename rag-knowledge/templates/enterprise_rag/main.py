"""
企业级 RAG 知识库 — 主入口

用法：
  python main.py              # 交互式对话
  python main.py --init       # 初始化向量库
  python main.py --api        # 启动 FastAPI 服务
"""

import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="企业级 RAG 知识库问答系统")
    parser.add_argument("--init", action="store_true", help="初始化向量库（重新导入文档）")
    parser.add_argument("--api", action="store_true", help="启动 FastAPI 服务")
    args = parser.parse_args()

    if args.init:
        from scripts.init_vectorstore import init
        init()
    elif args.api:
        import uvicorn
        from config.settings import settings
        uvicorn.run("api.main:app", host=settings.api_host, port=settings.api_port)
    else:
        # 交互模式
        from services.qa_service import ask
        from models.qa import QuestionRequest

        print("=" * 60)
        print("  企业级 RAG 知识库问答系统")
        print("  输入 'quit' 退出, 'sources' 查看上一条来源")
        print("=" * 60)
        print()

        history = []
        last_sources = []

        while True:
            try:
                user_input = input("你: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\n再见！")
                break

            if not user_input:
                continue
            if user_input.lower() == "quit":
                print("再见！")
                break
            if user_input.lower() == "sources":
                if last_sources:
                    for i, src in enumerate(last_sources, 1):
                        print(f"  [{i}] {src.chapter} > {src.section} (相关度: {src.score:.2f})")
                        print(f"      {src.snippet[:100]}...")
                else:
                    print("  暂无来源信息")
                continue

            req = QuestionRequest(question=user_input)
            resp = ask(req, session_history=history)

            last_sources = resp.sources
            print(f"AI: {resp.answer}")
            if resp.fallback_triggered:
                print(f"  [注意] 兜底回答，耗时 {resp.latency_ms:.0f}ms")
            else:
                print(f"  [检索到 {len(resp.sources)} 条来源，耗时 {resp.latency_ms:.0f}ms]")
            print()

            history.append(f"用户: {user_input}")
            history.append(f"系统: {resp.answer[:100]}")
