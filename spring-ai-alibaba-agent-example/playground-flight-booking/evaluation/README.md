# 最小评测模块（Interview-Friendly）

本目录提供一个**可解释、可复现、改动小**的 RAG 评测方案。

## 1. 文件说明

- `airline_policy_eval_zh.json`：中文评测集（24条）
  - 覆盖类别：`refund`、`reschedule`、`restrictions`、`out_of_scope`
- `run_eval.py`：自动化评测脚本
  - 通过 SSE 接口调用系统：`/api/assistant/chat`
  - 输出明细结果到 JSON 和 CSV
  - 终端打印汇总指标

## 2. 数据集 Schema

每条样本格式：

```json
{
  "id": "Q01",
  "category": "refund",
  "question": "...",
  "expected_intent": "in_scope | out_of_scope",
  "expected_keywords": ["关键词1", "关键词2"]
}
```

## 3. 评测指标（当前代码可行）

### Answer-level（已实现）

1. `keyword_score`：回答命中期望关键词比例（0~1）
2. `answer_pass`：
   - in-scope：`keyword_score >= 0.5`
   - out-of-scope：回答需包含 `非常抱歉` 和 `95557`
3. `avg_latency_ms_on_ok`：平均响应时延
4. 分类统计：每个 category 的 pass_rate 与平均 keyword_score

### Retrieval-level（当前不可直接做）

当前 `/api/assistant/chat` 只返回生成文本流，不返回检索文档、分数、引用信息，因此**无法严格计算 retrieval@k / recall@k**。

实践替代：使用 `keyword_score` + 越界拒答合规率作为代理指标。

## 4. 运行方式

先启动后端服务（需配置 `AI_DASHSCOPE_API_KEY`）：

```bash
mvn spring-boot:run
```

再执行评测：

```bash
python3 evaluation/run_eval.py \
  --base-url http://localhost:9000 \
  --dataset evaluation/airline_policy_eval_zh.json
```

## 5. 输出

- `evaluation/results/eval_results_*.json`
- `evaluation/results/eval_results_*.csv`

## 6. 面试讲解建议

可以用下面这句话：

> 在不改动核心业务代码的前提下，我加了一个最小评测闭环：中文政策问答数据集 + 自动化回归脚本 + 明细结果落盘 + 汇总指标输出。虽然当前接口未暴露检索证据，无法做严格 retrieval@k，但我用关键词覆盖率和越界拒答合规率做了可解释代理评估，并且这套脚本可以直接接入 CI 做版本回归。


## 7. 多源知识库升级后的评测建议

当前项目已支持从 `rag/` 目录加载多个 markdown 文件并写入 `source/doc_type/chunk_id` 元数据。
这意味着后续可以在数据集中逐步增加 `expected_sources` 字段（例如 `refund-policy.md`、`baggage-policy.md`），用于 source-level 检索评估（如 Hit@k）。

为保持最小改动，现有脚本仍可直接运行（answer-level 不受影响），你可以在下一步再升级脚本以读取 `expected_sources`。
