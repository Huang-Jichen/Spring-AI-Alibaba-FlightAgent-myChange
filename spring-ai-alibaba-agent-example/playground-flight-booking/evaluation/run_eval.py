#!/usr/bin/env python3
"""
最小可解释 RAG 评测脚本（面试友好版）

默认调用本项目 SSE 接口：/api/assistant/chat
- 输入：evaluation/airline_policy_eval_zh.json
- 输出：evaluation/results/eval_results_*.json + .csv
- 终端打印：总体指标 + 分类指标
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Tuple
from urllib.error import URLError, HTTPError
from urllib.parse import quote
from urllib.request import urlopen


@dataclass
class EvalSample:
    id: str
    category: str
    question: str
    expected_intent: str
    expected_keywords: List[str]


def load_dataset(dataset_path: Path) -> List[EvalSample]:
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    samples: List[EvalSample] = []
    for item in data:
        samples.append(
            EvalSample(
                id=item["id"],
                category=item["category"],
                question=item["question"],
                expected_intent=item["expected_intent"],
                expected_keywords=item.get("expected_keywords", []),
            )
        )
    return samples


def fetch_answer_sse(base_url: str, chat_id: str, question: str, timeout_s: int) -> Tuple[str, str]:
    """返回 (answer_text, error_message)。error_message 为空表示成功。"""
    q = quote(question, safe="")
    cid = quote(chat_id, safe="")
    url = f"{base_url.rstrip('/')}/api/assistant/chat?chatId={cid}&userMessage={q}"

    chunks: List[str] = []
    try:
        with urlopen(url, timeout=timeout_s) as resp:
            for raw_line in resp:
                line = raw_line.decode("utf-8", errors="ignore").strip()
                if not line:
                    continue
                if line.startswith("data:"):
                    chunks.append(line[len("data:"):].strip())
                elif line.startswith("event:") or line.startswith(":"):
                    continue
                else:
                    # 某些实现可能直接返回文本流
                    chunks.append(line)
    except HTTPError as e:
        return "", f"HTTPError {e.code}: {e.reason}"
    except URLError as e:
        return "", f"URLError: {e.reason}"
    except Exception as e:  # noqa
        return "", f"Exception: {e}"

    answer = "".join(chunks).strip()
    if not answer:
        return answer, "Empty answer"
    return answer, ""


def keyword_hit_score(answer: str, expected_keywords: List[str]) -> Tuple[float, List[str], List[str]]:
    if not expected_keywords:
        return 1.0, [], []
    hits, misses = [], []
    for kw in expected_keywords:
        if kw in answer:
            hits.append(kw)
        else:
            misses.append(kw)
    score = len(hits) / len(expected_keywords)
    return score, hits, misses


def judge_out_of_scope(answer: str) -> bool:
    # 与项目中 emptyContextPromptTemplate 对齐的最低约束关键词
    required = ["非常抱歉", "95557"]
    return all(k in answer for k in required)


def calc_summary(rows: List[Dict]) -> Dict:
    total = len(rows)
    ok_rows = [r for r in rows if r["status"] == "ok"]
    failed_rows = [r for r in rows if r["status"] != "ok"]

    avg_latency_ms = sum(r["latency_ms"] for r in ok_rows) / len(ok_rows) if ok_rows else 0.0
    avg_keyword_score = sum(r["keyword_score"] for r in ok_rows) / len(ok_rows) if ok_rows else 0.0

    answer_pass_rows = [r for r in ok_rows if r["answer_pass"]]
    answer_pass_rate = len(answer_pass_rows) / len(ok_rows) if ok_rows else 0.0

    cat_stats: Dict[str, Dict[str, float]] = {}
    categories = sorted(set(r["category"] for r in rows))
    for c in categories:
        c_rows = [r for r in rows if r["category"] == c and r["status"] == "ok"]
        if not c_rows:
            cat_stats[c] = {"count_ok": 0, "pass_rate": 0.0, "avg_keyword_score": 0.0}
            continue
        cat_stats[c] = {
            "count_ok": len(c_rows),
            "pass_rate": sum(1 for r in c_rows if r["answer_pass"]) / len(c_rows),
            "avg_keyword_score": sum(r["keyword_score"] for r in c_rows) / len(c_rows),
        }

    return {
        "total": total,
        "ok": len(ok_rows),
        "failed": len(failed_rows),
        "answer_pass_rate_on_ok": round(answer_pass_rate, 4),
        "avg_keyword_score_on_ok": round(avg_keyword_score, 4),
        "avg_latency_ms_on_ok": round(avg_latency_ms, 2),
        "category_metrics": cat_stats,
        "retrieval_evaluation": {
            "supported": False,
            "reason": "当前 /api/assistant/chat 只返回生成文本流，未暴露检索到的文档、分数和引用，无法做真实 retrieval@k。",
            "practical_alternative": "使用关键词覆盖率(keyword_score)和越界拒答合规率(out_of_scope pass)作为可解释代理指标。",
        },
    }


def ensure_output_dir(output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)


def write_outputs(output_dir: Path, rows: List[Dict], summary: Dict) -> Tuple[Path, Path]:
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    json_path = output_dir / f"eval_results_{ts}.json"
    csv_path = output_dir / f"eval_results_{ts}.csv"

    payload = {"summary": summary, "results": rows}
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    if rows:
        fieldnames = list(rows[0].keys())
        with csv_path.open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)

    return json_path, csv_path


def main() -> int:
    parser = argparse.ArgumentParser(description="运行最小RAG评测")
    parser.add_argument("--base-url", default="http://localhost:9000", help="服务地址，默认 http://localhost:9000")
    parser.add_argument("--dataset", default="evaluation/airline_policy_eval_zh.json", help="评测数据集路径")
    parser.add_argument("--output-dir", default="evaluation/results", help="结果输出目录")
    parser.add_argument("--timeout", type=int, default=60, help="单请求超时秒数")
    parser.add_argument("--sleep-ms", type=int, default=150, help="样本间隔，避免压垮模型")
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    output_dir = Path(args.output_dir)

    if not dataset_path.exists():
        print(f"[ERROR] 数据集不存在: {dataset_path}")
        return 2

    samples = load_dataset(dataset_path)
    ensure_output_dir(output_dir)

    rows: List[Dict] = []

    print(f"[INFO] 样本数: {len(samples)}")
    print(f"[INFO] 目标服务: {args.base_url}")

    for i, sample in enumerate(samples, start=1):
        chat_id = f"eval-{sample.id}-{uuid.uuid4().hex[:8]}"
        start = time.perf_counter()
        answer, error = fetch_answer_sse(args.base_url, chat_id, sample.question, args.timeout)
        latency_ms = (time.perf_counter() - start) * 1000

        if error:
            row = {
                "id": sample.id,
                "category": sample.category,
                "question": sample.question,
                "expected_intent": sample.expected_intent,
                "status": "error",
                "error": error,
                "latency_ms": round(latency_ms, 2),
                "answer": answer,
                "keyword_score": 0.0,
                "hit_keywords": "",
                "miss_keywords": "",
                "answer_pass": False,
            }
            rows.append(row)
            print(f"[{i:02d}/{len(samples)}] {sample.id} ❌ {error}")
            time.sleep(args.sleep_ms / 1000.0)
            continue

        kw_score, hits, misses = keyword_hit_score(answer, sample.expected_keywords)

        if sample.expected_intent == "out_of_scope":
            pass_flag = judge_out_of_scope(answer)
        else:
            pass_flag = kw_score >= 0.5

        row = {
            "id": sample.id,
            "category": sample.category,
            "question": sample.question,
            "expected_intent": sample.expected_intent,
            "status": "ok",
            "error": "",
            "latency_ms": round(latency_ms, 2),
            "answer": answer,
            "keyword_score": round(kw_score, 4),
            "hit_keywords": "|".join(hits),
            "miss_keywords": "|".join(misses),
            "answer_pass": pass_flag,
        }
        rows.append(row)

        state = "✅" if pass_flag else "⚠️"
        print(
            f"[{i:02d}/{len(samples)}] {sample.id} {state} "
            f"lat={row['latency_ms']}ms score={row['keyword_score']} cat={sample.category}"
        )

        time.sleep(args.sleep_ms / 1000.0)

    summary = calc_summary(rows)
    json_path, csv_path = write_outputs(output_dir, rows, summary)

    print("\n===== 评测汇总 =====")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n[INFO] JSON结果: {json_path}")
    print(f"[INFO] CSV结果 : {csv_path}")

    return 0 if summary["ok"] > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
