"""QA 请求/响应模型"""

from pydantic import BaseModel


class QuestionRequest(BaseModel):
    question: str
    filter_format: str | None = None  # 可选：只检索特定格式的文档


class SourceInfo(BaseModel):
    chapter: str = ""
    section: str = ""
    snippet: str = ""
    score: float = 0.0


class AnswerResponse(BaseModel):
    answer: str
    sources: list[SourceInfo] = []
    fallback_triggered: bool = False
    latency_ms: float = 0.0
