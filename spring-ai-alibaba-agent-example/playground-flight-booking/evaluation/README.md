# 双层RAG评测模块（Interview-Friendly）

本目录提供一个**最小、可解释、可复现**的双层评测方案：

1. Retrieval-level（检索层）
2. Generation-level（生成层）

并且不改动现有前端聊天流。

## 1. 为什么之前无法做检索评测

旧版评测只调用 `/api/assistant/chat`（SSE），该接口仅返回生成文本流，无法拿到 top-k 检索块、来源和排名，因此不能计算 Hit@k / MRR / Recall@k。

## 2. 现在如何支持检索评测

新增评测接口：`GET /api/assistant/eval`

该接口返回结构化 JSON：

- `question`
- `final_answer`
- `latency_ms`
- `retrieved_documents`（top-k）
  - `rank`（**始终存在**）
  - `content`
  - `source`
  - `doc_type`
  - `chunk_id`
  - `score`（如底层不可用则为 `null`）

这样就能在同一次调用里同时评估“检索质量”和“回答质量”。

## 3. 数据集 Schema（升级版）

`airline_policy_eval_zh.json` 每条样本格式：

```json
{
  "id": "Q01",
  "question": "...",
  "reference_answer": "...",
  "expected_sources": ["refund-policy.md"],
  "expected_evidence_keywords": ["退票", "手续费"],
  "expected_answer_keywords": ["168小时", "5%-20%"],
  "category": "refund",
  "expected_intent": "in_scope | out_of_scope"
}
```

## 4. 指标定义（重点）

### 4.1 Generation-level

1) `keyword_score`
- 定义：`命中的 expected_answer_keywords 数 / expected_answer_keywords 总数`
- 范围：`[0, 1]`

2) `answer_pass`
- 当 `expected_intent = in_scope`：`keyword_score >= 0.5` 视为通过
- 当 `expected_intent = out_of_scope`：由 `refusal_compliance` 决定（见下）

3) `refusal_compliance`（越界拒答合规）
- 仅对 `out_of_scope` 样本生效
- 当前最小规则：回答同时包含 `非常抱歉` 与 `95557` 记为合规

4) `latency_ms`
- 定义：评测接口单次请求耗时（毫秒）

### 4.2 Retrieval-level

1) `Hit@k (source-level)`
- 定义：top-k 结果里任一 `source` 命中 `expected_sources`

2) `Hit@k (evidence-level)`
- 定义：top-k 内容中是否出现任一 `expected_evidence_keywords`

3) `MRR (evidence-level)`
- 定义：首个命中证据关键词的文档倒数排名（`1/rank`），未命中记 `0`

4) `Recall@k (evidence-keywords)`
- 定义：top-k 内容覆盖的证据关键词比例
- 公式：`命中的 expected_evidence_keywords 数 / expected_evidence_keywords 总数`

## 5. 运行方式

先启动后端服务（需配置 `AI_DASHSCOPE_API_KEY`）：

```bash
mvn spring-boot:run
```

执行评测：

```bash
python3 evaluation/run_eval.py \
  --base-url http://localhost:9000 \
  --dataset evaluation/airline_policy_eval_zh.json \
  --top-k 5
```

## 6. 输出

- `evaluation/results/eval_results_*.json`
- `evaluation/results/eval_results_*.csv`
- 终端打印双层汇总：Retrieval + Generation

## 7. 仍然存在的限制

- `score` 字段是否可用取决于底层向量库返回的 metadata；不可用时会是 `null`。
- `expected_sources` 与 `expected_evidence_keywords` 属于轻量标签，适合工程回归和面试展示，不等同于学术级人工标注基准。

## 8. 面试讲法（可直接复述）

> 我把RAG评测拆成两层：检索层和生成层。新增了一个专用评测接口 `/api/assistant/eval`，能返回 top-k 检索块及元数据（包含 rank/source/chunk_id）。然后评测脚本同时计算检索指标（Hit@k、MRR、Recall@k）和生成指标（answer_pass、keyword_score、refusal_compliance、latency），形成可持续回归的双层评测闭环。
