"""
集中配置管理
所有参数一处改，不留硬编码
"""

import os
from pathlib import Path
from pydantic_settings import BaseSettings
from dotenv import load_dotenv

load_dotenv(override=True)


class Settings(BaseSettings):
    # ---- LLM ----
    llm_model: str = "deepseek-chat"
    llm_api_key: str = os.getenv("DEEPSEEK_API_KEY", "")
    llm_base_url: str = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    llm_temperature: float = 0.0

    # ---- Embedding ----
    embed_model_path: str = os.getenv("EMBED_MODEL_PATH", "")
    embed_device: str = os.getenv("EMBED_DEVICE", "cpu")

    # ---- Vector Store ----
    chroma_persist_dir: str = os.getenv("CHROMA_PERSIST_DIR", "./chroma_db")
    chroma_collection_name: str = os.getenv("CHROMA_COLLECTION_NAME", "enterprise_knowledge")

    # ---- Retrieval ----
    retrieval_top_k: int = 10
    retrieval_final_k: int = 3
    similarity_threshold: float = 0.6  # 提高默认阈值，减少噪音
    rrf_k: int = 60                    # RRF 平滑因子

    # ---- Chunking ----
    chunk_size: int = 800              # 提高默认值，保留更多上下文
    chunk_overlap: int = 120

    # ---- Query Rewriting ----
    rewrite_enabled: bool = True
    rewrite_num_queries: int = 3

    # ---- API ----
    api_host: str = "0.0.0.0"
    api_port: int = 8000
    log_level: str = "INFO"

    # ---- Evaluation ----
    ragas_test_cases_path: str = "./evaluation/accuracy/test_cases.json"

    class Config:
        env_file = ".env"


settings = Settings()
