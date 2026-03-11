#!/usr/bin/env python3
"""
双层RAG评测脚本（Retrieval + Generation）

默认调用评测接口：/api/assistant/eval
- 输入：evaluation/airline_policy_eval_zh.json
- 输出：evaluation/results/eval_results_*.json + .csv
- 终端打印：检索指标 + 生成指标
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
    question: str
    reference_answer: str
    expected_sources: List[str]
    expected_evidence_keywords: List[str]
    expected_answer_keywords: List[str]
    category: str
    expected_intent: str


def load_dataset(dataset_path: Path) -> List[EvalSample]:
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    samples: List[EvalSample] = []
    for item in data:
        samples.append(
            EvalSample(
                id=item["id"],
                question=item["question"],
                reference_answer=item.get("reference_answer", ""),
                expected_sources=item.get("expected_sources", []),
                expected_evidence_keywords=item.get("expected_evidence_keywords", []),
                expected_answer_keywords=item.get("expected_answer_keywords", item.get("expected_keywords", [])),
                category=item["category"],
                expected_intent=item.get("expected_intent", "in_scope"),
            )
        )
    return samples


def call_eval_endpoint(base_url: str, chat_id: str, question: str, top_k: int, timeout_s: int) -> Tuple[Dict, str]:
    q = quote(question, safe="")
    cid = quote(chat_id, safe="")
    url = f"{base_url.rstrip('/')}/api/assistant/eval?chatId={cid}&userMessage={q}&topK={top_k}"
    try:
        with urlopen(url, timeout=timeout_s) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            return payload, ""
    except HTTPError as e:
        return {}, f"HTTPError {e.code}: {e.reason}"
    except URLError as e:
        return {}, f"URLError: {e.reason}"
    except Exception as e:  # noqa
        return {}, f"Exception: {e}"


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


def refusal_compliance(answer: str, expected_intent: str) -> bool:
    if expected_intent != "out_of_scope":
        return True
    required = ["非常抱歉", "95557"]
    return all(k in answer for k in required)


def retrieval_source_hit(retrieved_docs: List[Dict], expected_sources: List[str]) -> bool:
    if not expected_sources:
        return True
    expected = set(expected_sources)
    for doc in retrieved_docs:
        if doc.get("source") in expected:
            return True
    return False


def retrieval_evidence_hit(retrieved_docs: List[Dict], expected_evidence_keywords: List[str]) -> bool:
    if not expected_evidence_keywords:
        return True
    merged = "\n".join((d.get("content") or "") for d in retrieved_docs)
    return any(kw in merged for kw in expected_evidence_keywords)


def retrieval_evidence_mrr(retrieved_docs: List[Dict], expected_evidence_keywords: List[str]) -> float:
    if not expected_evidence_keywords:
        return 1.0
    for idx, doc in enumerate(retrieved_docs, start=1):
        content = doc.get("content") or ""
        if any(kw in content for kw in expected_evidence_keywords):
            return 1.0 / idx
    return 0.0


def retrieval_evidence_recall(retrieved_docs: List[Dict], expected_evidence_keywords: List[str]) -> float:
    if not expected_evidence_keywords:
        return 1.0
    merged = "\n".join((d.get("content") or "") for d in retrieved_docs)
    hit_cnt = sum(1 for kw in expected_evidence_keywords if kw in merged)
    return hit_cnt / len(expected_evidence_keywords)


def calc_summary(rows: List[Dict]) -> Dict:
    total = len(rows)
    ok_rows = [r for r in rows if r["status"] == "ok"]

    def safe_avg(values: List[float]) -> float:
        return round(sum(values) / len(values), 4) if values else 0.0

    retrieval_metrics = {
        "hit_at_k_source": safe_avg([1.0 if r["retrieval_hit_source"] else 0.0 for r in ok_rows]),
        "hit_at_k_evidence": safe_avg([1.0 if r["retrieval_hit_evidence"] else 0.0 for r in ok_rows]),
        "mrr_evidence": safe_avg([r["retrieval_mrr"] for r in ok_rows]),
        "recall_at_k_evidence_keywords": safe_avg([r["retrieval_recall"] for r in ok_rows]),
    }

    generation_rows = ok_rows
    generation_metrics = {
        "answer_accuracy": safe_avg([1.0 if r["answer_pass"] else 0.0 for r in generation_rows]),
        "keyword_coverage": safe_avg([r["keyword_score"] for r in generation_rows]),
        "refusal_compliance": safe_avg([1.0 if r["refusal_compliance"] else 0.0 for r in generation_rows]),
        "avg_latency_ms": round(sum(r["latency_ms"] for r in generation_rows) / len(generation_rows), 2)
        if generation_rows
        else 0.0,
    }

    return {
        "total": total,
        "ok": len(ok_rows),
        "failed": total - len(ok_rows),
        "retrieval_metrics": retrieval_metrics,
        "generation_metrics": generation_metrics,
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
    parser = argparse.ArgumentParser(description="运行双层RAG评测")
    parser.add_argument("--base-url", default="http://localhost:9000", help="服务地址")
    parser.add_argument("--dataset", default="evaluation/airline_policy_eval_zh.json", help="评测数据集路径")
    parser.add_argument("--output-dir", default="evaluation/results", help="结果输出目录")
    parser.add_argument("--timeout", type=int, default=60, help="单请求超时秒数")
    parser.add_argument("--sleep-ms", type=int, default=150, help="样本间隔")
    parser.add_argument("--top-k", type=int, default=5, help="评测端检索top-k")
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
    print(f"[INFO] 检索TopK: {args.top_k}")

    for i, sample in enumerate(samples, start=1):
        chat_id = f"eval-{sample.id}-{uuid.uuid4().hex[:8]}"
        payload, err = call_eval_endpoint(args.base_url, chat_id, sample.question, args.top_k, args.timeout)

        if err:
            row = {
                "id": sample.id,
                "category": sample.category,
                "question": sample.question,
                "status": "error",
                "error": err,
                "latency_ms": 0.0,
                "retrieved_sources": "",
                "retrieved_chunk_ids": "",
                "retrieval_hit_source": False,
                "retrieval_hit_evidence": False,
                "retrieval_mrr": 0.0,
                "retrieval_recall": 0.0,
                "keyword_score": 0.0,
                "answer_pass": False,
                "refusal_compliance": False,
                "final_answer": "",
            }
            rows.append(row)
            print(f"[{i:02d}/{len(samples)}] {sample.id} ❌ {err}")
            time.sleep(args.sleep_ms / 1000.0)
            continue

        final_answer = payload.get("final_answer", "") or ""
        latency_ms = float(payload.get("latency_ms") or 0.0)
        retrieved_docs = payload.get("retrieved_documents") or []

        source_hit = retrieval_source_hit(retrieved_docs, sample.expected_sources)
        evidence_hit = retrieval_evidence_hit(retrieved_docs, sample.expected_evidence_keywords)
        mrr = retrieval_evidence_mrr(retrieved_docs, sample.expected_evidence_keywords)
        recall = retrieval_evidence_recall(retrieved_docs, sample.expected_evidence_keywords)

        keyword_score, answer_hits, answer_misses = keyword_hit_score(final_answer, sample.expected_answer_keywords)
        refuse_ok = refusal_compliance(final_answer, sample.expected_intent)
        if sample.expected_intent == "out_of_scope":
            answer_pass = refuse_ok
        else:
            answer_pass = keyword_score >= 0.5

        row = {
            "id": sample.id,
            "category": sample.category,
            "question": sample.question,
            "status": "ok",
            "error": "",
            "latency_ms": round(latency_ms, 2),
            "retrieved_sources": "|".join(str(d.get("source")) for d in retrieved_docs),
            "retrieved_chunk_ids": "|".join(str(d.get("chunk_id")) for d in retrieved_docs),
            "retrieval_hit_source": source_hit,
            "retrieval_hit_evidence": evidence_hit,
            "retrieval_mrr": round(mrr, 4),
            "retrieval_recall": round(recall, 4),
            "keyword_score": round(keyword_score, 4),
            "answer_hit_keywords": "|".join(answer_hits),
            "answer_miss_keywords": "|".join(answer_misses),
            "answer_pass": answer_pass,
            "refusal_compliance": refuse_ok,
            "final_answer": final_answer,
        }
        rows.append(row)

        state = "✅" if answer_pass and source_hit else "⚠️"
        print(
            f"[{i:02d}/{len(samples)}] {sample.id} {state} "
            f"src_hit={source_hit} ev_hit={evidence_hit} mrr={row['retrieval_mrr']} ans={answer_pass}"
        )
        time.sleep(args.sleep_ms / 1000.0)

    summary = calc_summary(rows)
    json_path, csv_path = write_outputs(output_dir, rows, summary)

    print("\nEvaluation Summary")
    print(f"Total questions: {summary['total']}")
    print("\nRetrieval Metrics")
    print(f"Hit@{args.top_k} (source-level): {summary['retrieval_metrics']['hit_at_k_source']}")
    print(f"Hit@{args.top_k} (evidence-level): {summary['retrieval_metrics']['hit_at_k_evidence']}")
    print(f"MRR: {summary['retrieval_metrics']['mrr_evidence']}")
    print(f"Recall@{args.top_k} (evidence-keywords): {summary['retrieval_metrics']['recall_at_k_evidence_keywords']}")

    print("\nGeneration Metrics")
    print(f"Answer Accuracy: {summary['generation_metrics']['answer_accuracy']}")
    print(f"Keyword Coverage: {summary['generation_metrics']['keyword_coverage']}")
    print(f"Refusal Compliance: {summary['generation_metrics']['refusal_compliance']}")
    print(f"Average Latency(ms): {summary['generation_metrics']['avg_latency_ms']}")

    print(f"\n[INFO] JSON结果: {json_path}")
    print(f"[INFO] CSV结果 : {csv_path}")

    return 0 if summary["ok"] > 0 else 1


if __name__ == "__main__":
    sys.exit(main())