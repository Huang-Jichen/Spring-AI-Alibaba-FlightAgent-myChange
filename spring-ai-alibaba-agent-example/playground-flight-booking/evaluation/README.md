# 双层RAG评测模块（Interview-Friendly）

本目录提供一个**最小、可解释、可复现**的评测方案：

1. Retrieval-only（仅检索）
2. Retrieval + Generation（全链路）

并且不改动现有前端聊天流。

## 1. 为什么要做 Retrieval-only 评测

当外部模型服务（如 DashScope）出现超时、限流或额度问题时，全链路评测会被生成阶段干扰，难以稳定判断检索质量。

因此新增独立端点：`GET /api/assistant/retrieval`，只评估向量检索，不调用 LLM。

这能帮助你单独调优 RAG 检索参数（如 `topK`、切分粒度、召回策略），并获得可重复的对比结果。

## 2. 接口与返回结构

### 2.1 Retrieval-only 端点

`GET /api/assistant/retrieval`

返回 JSON：

- `question`
- `top_k`
- `retrieval_ms`
- `retrieved_documents`
  - `rank`
  - `content`
  - `source`
  - `doc_type`
  - `chunk_id`
  - `score`（底层向量库不提供时为 `null`）

### 2.2 全链路评测端点

`GET /api/assistant/eval`

用于检索 + 生成联合评测。

## 3. 数据集 Schema（复用）

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

Retrieval-only 脚本主要使用：

- `question`
- `expected_sources`
- `expected_evidence_keywords`
- `category`

## 4. Retrieval-only 指标定义

1) `Hit@k (source-level)`
- top-k 文档中任一 `source` 命中 `expected_sources` 记 1，否则 0。

2) `Hit@k (evidence-level)`
- top-k 文档内容中出现任一 `expected_evidence_keywords` 记 1，否则 0。

3) `MRR (evidence-level)`
- 首个命中 evidence keyword 的文档排名为 `r`，得分 `1/r`；未命中记 0。

4) `Recall@k (evidence-keywords)`
- top-k 内容覆盖到的 evidence keyword 比例：
- `命中关键词数 / expected_evidence_keywords 总数`

5) `average retrieval latency`
- 所有样本 `retrieval_ms` 的均值。

## 5. 运行方式

先启动后端服务：

```bash
mvn spring-boot:run
```

### 5.1 仅检索评测（不依赖 DashScope）

```bash
python3 evaluation/run_retrieval_eval.py \
  --base-url http://127.0.0.1:9000 \
  --dataset evaluation/airline_policy_eval_zh.json \
  --top-k 5
```

可选参数：

- `--chat-id retrieval-eval`
- `--timeout-sec 15`
- `--output-dir evaluation/results`

### 5.2 全链路评测

```bash
python3 evaluation/run_eval.py \
  --base-url http://127.0.0.1:9000 \
  --dataset evaluation/airline_policy_eval_zh.json \
  --top-k 5
```

## 6. 输出

Retrieval-only 脚本输出：

- `evaluation/results/retrieval_eval_results_*.json`
- `evaluation/results/retrieval_eval_results_*.csv`

终端会打印汇总：

- `Hit@k (source)`
- `Hit@k (evidence)`
- `MRR (evidence)`
- `Recall@k (evidence)`
- `Avg retrieval ms`

## 7. 这如何支持 RAG 调优

你可以固定数据集，反复调整检索参数并对比上述指标：

- 调 `topK`：观察召回上升与噪声上升的平衡
- 调切分策略：观察 `MRR` 和 `Recall@k` 是否提升
- 调向量库/索引策略：观察 `Hit@k` 与延迟变化

这样能在生成模型不稳定时，先把检索层单独做实，再做全链路优化。

## 8. 面试讲法（可直接复述）

> 我把 RAG 评测拆成了 retrieval-only 和全链路两层。新增 `/api/assistant/retrieval` 后，用脚本独立计算 Hit@k、MRR、Recall@k 和检索延迟，不依赖外部 LLM。这样即使生成服务波动，也能稳定地优化检索层，形成可重复、可解释的调优闭环。

## Retrieval Parameter Experiment (topK)

实验脚本：`evaluation/run_retrieval_eval.py`  
评测数据集：`evaluation/airline_policy_eval_zh.json`  
每组样本数：24

| topK | samples_total | samples_failed | hit_at_k_source | hit_at_k_evidence | mrr_evidence | recall_at_k_evidence | avg_retrieval_ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| 3 | 24 | 1 | 0.7500 | 0.7500 | 0.7500 | 0.7500 | 679.91 |
| 5 | 24 | 0 | 0.7917 | 0.7917 | 0.7917 | 0.7917 | 692.21 |
| 8 | 24 | 2 | 0.7083 | 0.7083 | 0.7083 | 0.7083 | 1727.55 |

- `topK=5` 表现最优：命中率、MRR、Recall 均为最高且 `samples_failed=0`，说明召回覆盖与结果稳定性达到较好平衡。
- `topK` 增大到 8 后引入更多低相关文档：候选集合噪声上升，导致证据命中排名下滑，整体检索指标反而下降。
- 延迟随 `topK` 增大而上升：需要返回和处理更多文档块，向量检索与序列化开销变大，`topK=8` 延迟显著增加。

**结论**

- 当前最佳配置为 `topK=5`（效果和稳定性最优）。
- `topK=3` 略快，但覆盖率和稳定性略弱于 `topK=5`。
- `topK=8` 带来不必要的噪声与明显延迟，不建议作为默认配置。
