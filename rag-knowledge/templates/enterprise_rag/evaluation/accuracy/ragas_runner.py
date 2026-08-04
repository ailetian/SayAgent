"""
RAGAS 准确率评测

用法: python evaluation/accuracy/ragas_runner.py

评测 4 项指标：
  Faithfulness       — 回答是否忠实于文档（有没有编造）
  Answer Relevancy   — 回答跟问题是否对得上
  Context Precision  — 检索到的片段有多少是真正相关的
  Context Recall     — 标准答案里的关键信息检索有没有命中

验收标准（可根据场景调整）：
  内部工具: ≥ 0.75   业务系统: ≥ 0.80   对外服务: ≥ 0.85
"""

import json
from pathlib import Path
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall

# 验收阈值（对外服务标准，可根据项目调整）
THRESHOLDS = {
    "faithfulness": 0.85,
    "answer_relevancy": 0.80,
    "context_precision": 0.85,
    "context_recall": 0.85,
}


def load_test_cases(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def run_evaluation(test_cases: list[dict]) -> dict:
    dataset = Dataset.from_dict({
        "question": [tc["question"] for tc in test_cases],
        "ground_truth": [tc["ground_truth"] for tc in test_cases],
        "contexts": [tc["contexts"] for tc in test_cases],
        "answer": [tc["answer"] for tc in test_cases],
    })

    result = evaluate(
        dataset,
        metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
    )

    return {k: float(result[k]) for k in THRESHOLDS}


def print_report(scores: dict):
    print("=" * 60)
    print("  RAGAS 准确率评测报告")
    print("=" * 60)

    passed = 0
    for metric, value in scores.items():
        target = THRESHOLDS.get(metric, 0.8)
        status = "PASS" if value >= target else "FAIL"
        if status == "PASS":
            passed += 1
        print(f"  {metric:22s}: {value:.3f}  (目标: ≥{target})  [{status}]")

    print("=" * 60)
    print(f"  通过: {passed}/{len(scores)}")
    avg = sum(scores.values()) / len(scores)
    print(f"  综合平均分: {avg:.3f}")
    print()


if __name__ == "__main__":
    test_cases_path = Path(__file__).parent / "test_cases.json"
    if test_cases_path.exists():
        cases = load_test_cases(str(test_cases_path))
        scores = run_evaluation(cases)
        print_report(scores)
    else:
        print(f"评测用例文件不存在: {test_cases_path}")
