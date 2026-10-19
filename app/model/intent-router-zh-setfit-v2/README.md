---
license: cc-by-nc-4.0
library_name: setfit
pipeline_tag: text-classification
tags:
  - setfit
  - sentence-transformers
  - text-classification
  - intent-classification
  - qwen3
  - jina-embeddings-v5
  - chinese
  - coding
language:
  - zh
  - en
base_model: jinaai/jina-embeddings-v5-text-small-classification
---

# intent-router-zh-setfit-v2

SetFit intent router for AI coding-agent traffic. Fine-tuned from
[jinaai/jina-embeddings-v5-text-small-classification](https://huggingface.co/jinaai/jina-embeddings-v5-text-small-classification)
(Qwen3-0.6B backbone, last-token pooling, task prompts). It maps a user prompt
to **21 intents** across `coding` / `ops` / `general_control` plus
`out_of_scope`, and ships with a **v5 routing policy** (temperature scaling,
argmax routing — see [Routing policy](#end-to-end-routing-recommended)).

> **License note:** this checkpoint is fine-tuned from a **CC BY-NC 4.0** base
> model, so the derived weights are **non-commercial only**.
>
> 主评测集 **gold_test n=695**：
> closed-set / routed **0.699** · macro-F1 **0.554** · domain **0.875**。  
> 独立短指令 coding OOD **n=360**：closed **0.783**。  
> 弱类长尾切片 **n=180**：closed **0.694**。  
> action–object probe **n=45**：**1.000**。

## Datasets & test sizes (release record)

| Split / set | n | Role | Notes |
|---|---:|---|---|
| Labeled pool (audited) | 7284 | 5767 real + **1517 synthetic** additions | min **150**/class after augmentation |
| Train | **5237** | Contrastive body + linear head | capped body 4306; distribution below |
| Calibration | 473 | Policy temperature / thresholds | |
| Rule validation | 416 | Keyword mining validation | |
| **Gold test (release)** | **695** | Primary metrics | frozen; sha256 `292ebd29…ffa15a0` |
| Action–object probe | 45 | Held-out disambiguation | 15 objects × 3 actions |
| Long-tail coverage slice | 180 | Weak-class stress test | 9 weak classes × 20; **not** in train |
| Independent coding OOD | 360 | Short-instruction stress test | 9 coding × 40; **0** leakage vs train/calib/rule/gold/probe (re-verified) |

Synthetic additions come from the LLM augmentation program (planned 936:
216 short coding instructions, 580 weak-class workflows, 140 near-neighbor
boundary cases) plus long-tail, minority, OOS-hard-negative, and short
Chinese code-search generators. Reserved sets (gold, calibration, probes,
long-tail slice) were excluded from generation by contract.

### Train distribution (n=5237)

| Intent | n | | Intent | n |
|---|---:|---|---|---:|
| code_review | 671 | | infrastructure_provisioning | 200 |
| code_modification | 450 | | architecture_consultation | 196 |
| monitoring_query | 391 | | log_analysis | 194 |
| code_explanation | 372 | | config_change | 179 |
| code_authoring | 285 | | security_operation | 174 |
| code_search | 262 | | pipeline_operation | 163 |
| code_repair | 242 | | incident_response | 160 |
| context_specification | 227 | | general_inquiry | 157 |
| dependency_management | 218 | | deployment | 157 |
| out_of_scope | 206 | | test_generation | 201 |
| | | | workflow_control | 132 |

Gold composition: coding 466 (67.1%) / ops 166 (23.9%) / general_control
56 (8.1%) / out_of_scope 7 (1.0%); length median 290 chars, max 36248.

## Labels

| Domain | Intents |
|---|---|
| coding (9) | `code_authoring`, `code_modification`, `code_repair`, `code_review`, `code_explanation`, `test_generation`, `code_search`, `architecture_consultation`, `dependency_management` |
| ops (8) | `deployment`, `infrastructure_provisioning`, `monitoring_query`, `incident_response`, `pipeline_operation`, `config_change`, `security_operation`, `log_analysis` |
| general_control (3) | `context_specification`, `workflow_control`, `general_inquiry` |
| fallback | `out_of_scope` |

## Intended use

- Route prompts of an AI **coding** assistant to specialized handlers
  (zh / en / mixed, short imperatives through long agent-style context).
- **Max input length for best quality: 1024 tokens** (training setting).
- Not a general-purpose NLU router: OOS support in gold is only 7 rows, and
  `general_control` remains the weakest domain (0.500 intent accuracy on gold).

## Usage (SetFit / PyTorch)

```python
import os
os.environ["SETFIT_DEVICE"] = "cpu"
import torch
from setfit import SetFitModel

model = SetFitModel.from_pretrained(
    "snival/intent-router-zh-setfit-v2",
    trust_remote_code=True,
)
model.to(torch.device("cpu"))

body = model.model_body
body.max_seq_length = 1024
body.tokenizer.model_max_length = 1024
transformer = body[0]
transformer.max_seq_length = 1024
transformer.tokenizer.model_max_length = 1024
transformer.tokenizer.padding_side = "left"

probas = model.predict_proba([
    "帮我 review 一下这个 PR，重点关注线程安全",
    "cargo add serde --features derive，然后更新 lockfile",
])
```

Framework-free path: encode with the body, then apply `head_coef.npy` /
`head_intercept.npy` + softmax. For CPU/edge deployment without PyTorch, see
[ONNX / ONNX Runtime](#onnx--onnx-runtime-cpu).

### ONNX / ONNX Runtime (CPU)

Use the encoder graphs shipped with this checkpoint; ONNX exports from other
checkpoints are **not** compatible. **Prompt contract**: the Jina v5 base
prepends the prompt prefix `"Document: "` to every text inside
`SentenceTransformer.encode()` — prepend the same prefix before tokenizing
(plain string concatenation) or embeddings drift from the SetFit-native path.
The prefix is recorded in `head_meta.json` (`prompt_prefix`). Left padding,
`max_length=1024`; the head is **not** fused into ONNX — apply
`head_coef.npy` + `head_intercept.npy` + softmax after `sentence_embedding`.
Details: [`ONNX_INFERENCE.md`](ONNX_INFERENCE.md).

| Artifact | Size | Role | Gold n=695 vs fp32 ONNX |
|---|---:|---|---|
| `encoder.onnx` + `encoder.onnx.data` | 4.6 MB + 2.4 GB | FP32 reference (external data) | — (matches PyTorch/SetFit at 7.5e-08) |
| `encoder-woq8.onnx` + `.data` | 19 MB + 596 MB | **Recommended compact path, not latency path** (block-wise int8, MatMulNBits block=128 + per-row int8 token table) | argmax agreement **99.7%**, cosine min 0.9946 / mean 0.99994, gold accuracy 0.698 vs 0.696 |

**Not shipped**: dynamic-int8 (`quantize_dynamic`) fails on this base model —
quantizing the token-embedding table (single-scale UINT8) or per-channel MatMul
weights distorts embeddings (gold argmax agreement 92.5–92.9%, cosine p5 ≈ 0.8).
`quantize_int8.py` now enforces a real-data release gate and refuses such
artifacts; use `quantize_woq8.py` output instead.

Speed on the eval CPU (i9-14900KF, 32 threads; ONNX Runtime CPUExecutionProvider):

| Path | Latency p50 / p90 | Short-text throughput (batch=8) | Long-text throughput (batch=8) |
|---|---|---:|---:|
| `encoder.onnx` (fp32) | 82 / 319 ms | **4.19 samples/s** | 0.61 samples/s |
| `encoder-woq8.onnx` | 489 / 724 ms | 3.23 samples/s | 0.57 samples/s |

On this CPU, woq8 trades ~23% throughput for a **3.9× smaller** artifact;
pick fp32 for latency-critical serving and woq8 where footprint dominates.

### End-to-end routing (recommended)

Ship files: `labels.json`, `metrics.json`, `rejection_policy.json` (**v5**).
Reference: `routing_policy.decide_route()`.

1. Temperature scaling (`temperature ≈ 2.396`)
2. Short-text rejection is **disabled**: it also requires a positive score threshold, while `max_probability=0.0`
3. Score-threshold rejection is **off** (`max_probability=0.0`, `min_margin=0.0`)
4. Keyword and flip routing are **disabled** (`enable_keyword_rules=false`, `enable_flip_rules=false`)

The shipped v5 policy is therefore calibrated argmax routing. Do not claim an
active OOS/short-text safety fallback unless thresholds are re-calibrated and
enabled on production traffic.

Why disabled: on gold, enabling keyword rules **drops** routed accuracy from
**0.6993 to 0.6705** — the base model makes them net-negative. The mined
patterns are also near-verbatim phrases from the training corpus, so they are
**not shipped** in `rejection_policy.json` (`keyword_rules: []`).

## Evaluation

All numbers below were recomputed on CPU (Intel i9-14900KF, 32 threads,
PyTorch fp32, `max_length=1024`) from the shipped checkpoint and cached
probability arrays. CPU↔training-machine agreement: closed-set 0.6993 vs
`metrics.json` 0.6964 (2/695 samples differ — numeric drift only).

### 1) Gold test — n=695 (primary)

| Metric | Closed-set | Routed (v5) |
|---|---:|---:|
| Accuracy | **0.699** | **0.699** |
| Macro-F1 | **0.554** | 0.554 (all) / **0.563** (in-scope) |
| Domain accuracy | 0.875 | **0.875** |
| OOS precision / recall / F1 | — | 0.500 / 0.286 / 0.364 (support **7**) |

Routed equals closed-set because v5 is argmax-only (no keyword/flip overrides);
on gold, route reasons were 691 `argmax` + 4 `predicted_out_of_scope`.

By length (chars):

| Length | n | Closed-set |
|---|---:|---:|
| <100 | 240 | 0.650 |
| 100–800 | 180 | 0.606 |
| 800+ | 275 | **0.804** |

Intent accuracy by true domain (closed = routed):

| Domain | n | Accuracy |
|---|---:|---:|
| coding | 466 | **0.732** |
| ops | 166 | 0.693 |
| general_control | 56 | 0.500 |
| out_of_scope | 7 | 0.286 |

Per-class routed (precision / recall / F1 / support):

| Intent | P | R | F1 | n |
|---|---:|---:|---:|---:|
| `code_review` | 0.892 | 0.946 | **0.919** | 149 |
| `monitoring_query` | 0.877 | 0.909 | **0.893** | 55 |
| `security_operation` | 0.929 | 0.867 | **0.897** | 15 |
| `code_authoring` | 0.800 | 0.720 | 0.758 | 50 |
| `deployment` | 0.762 | 0.727 | 0.744 | 22 |
| `code_modification` | 0.556 | 0.833 | 0.667 | 84 |
| `context_specification` | 0.750 | 0.677 | 0.712 | 31 |
| `infrastructure_provisioning` | 0.727 | 0.571 | 0.640 | 14 |
| `code_explanation` | 0.644 | 0.635 | 0.640 | 74 |
| `log_analysis` | 0.529 | 0.692 | 0.600 | 26 |
| `architecture_consultation` | 0.640 | 0.516 | 0.571 | 31 |
| `code_search` | 0.800 | 0.375 | 0.511 | 32 |
| `code_repair` | 0.516 | 0.421 | 0.464 | 38 |
| `incident_response` | 0.400 | 0.444 | 0.421 | 9 |
| `test_generation` | 0.333 | 0.400 | 0.364 | 5 |
| `config_change` | 0.500 | 0.316 | 0.387 | 19 |
| `workflow_control` | 0.400 | 0.364 | 0.381 | 11 |
| `out_of_scope` | 0.500 | 0.286 | 0.364 | 7 |
| `dependency_management` | 0.500 | 0.333 | 0.400 | 3 |
| `general_inquiry` | 0.500 | 0.214 | 0.300 | 14 |
| `pipeline_operation` | 0.000 | 0.000 | **0.000** | 6 |

Top closed-set confusions: `code_search → code_explanation` (14) ·
`code_authoring → code_modification` (12) · `code_repair → log_analysis` (7) ·
`code_explanation → code_modification` / `→ code_review` (7) ·
`config_change → code_modification` (7).

Calibration: raw ECE **0.276** / NLL **2.534** → after policy temperature
(T=2.396) ECE **0.102** / NLL **1.334**. The model is strongly overconfident:
**65.6% of gold errors carry confidence ≥ 0.99**. Treat raw scores as rankings,
not probabilities; re-fit temperature per deployment if you need abstention.

### 2) Long-tail coverage slice — n=180 (weak-class stress)

Held-out rows for the 9 historically weakest classes (20 each), never in train.

| Metric | Value |
|---|---:|
| Closed-set = routed accuracy | **0.694** |
| Domain accuracy | 0.822 |
| OOS precision / recall / F1 | 1.000 / 0.750 / **0.857** |

Per-class recall: `infrastructure_provisioning` **0.85** · `workflow_control`
**0.85** · `incident_response` 0.80 · `pipeline_operation` 0.70 ·
`security_operation` 0.70 · `test_generation` 0.65 · `dependency_management`
0.60 · `general_inquiry` 0.35 (top confusions: `general_inquiry →
architecture_consultation` 7, `dependency_management → code_modification` 4).

### 3) Independent coding OOD — n=360 (short-instruction stress)

A frozen independent set of short standalone coding instructions (median
204 chars; leakage re-verified **0** against train/calibration/
rule-validation/gold/probe).

| Metric | Closed | Routed |
|---|---:|---:|
| Accuracy | **0.783** | **0.783** |
| Macro-F1 | **0.793** | **0.793** |

| Intent | P | R | F1 |
|---|---:|---:|---:|
| code_explanation | 0.884 | 0.950 | **0.916** |
| code_authoring | 0.971 | 0.825 | 0.892 |
| test_generation | 0.943 | 0.825 | 0.880 |
| code_repair | 0.872 | 0.850 | 0.861 |
| code_review | 0.706 | 0.900 | 0.791 |
| code_modification | 0.717 | 0.825 | 0.767 |
| code_search | 1.000 | 0.625 | 0.769 |
| architecture_consultation | 0.643 | 0.900 | 0.750 |
| dependency_management | 0.933 | 0.350 | **0.509** |

Slices: easy 0.782 / medium 0.806 / hard 0.722; zh 0.775 / en 0.791 /
mixed 0.781. Dominant confusion: `dependency_management →
architecture_consultation` (17).

### 4) Action–object probe — n=45

Held-out triples holding the *object* fixed while varying the *action*
(explain / search / monitor the same resource): accuracy **1.000**,
macro-F1 **1.000**.

### 5) CPU speed (Intel i9-14900KF, 32 threads, fp32)

| Metric | Value |
|---|---|
| Cold start (warm local weights) | ~0.2 s |
| Single-sample latency p50 / p90 / p99 | **107 / 493 / 653 ms** |
| Throughput, short prompts (batch=8, n=360) | **4.40 samples/s** |
| Throughput, gold long prompts (batch=16, n=695) | 0.8 samples/s |
| Weights | 2.3 GB fp32 (`model.safetensors`) |

Expect ~1.2 s/batch at the 1024-token ceiling. ONNX CPU paths (fp32 and woq8)
are benchmarked in the [ONNX section](#onnx--onnx-runtime-cpu).

## Limitations

- **Overconfident**: calibrated ECE 0.102 with 65.6% of errors at ≥0.99
  confidence. Do not use raw max-probability as an abstention threshold
  without re-calibration.
- **`general_control` is the weakest domain** (gold intent accuracy 0.500);
  `general_inquiry` recall is 0.21–0.35 across sets.
- `pipeline_operation` is 0.000 F1 on gold (support 6) despite 0.70 recall
  on the long-tail slice — gold samples are agent-style, the slice is
  imperative-style.
- `code_search` recall on gold is 0.375 (top confusion → `code_explanation`).
- `dependency_management` recall collapses to 0.35 on architecture-flavored
  phrasing (17/40 → `architecture_consultation` on the coding OOD set).
- OOS support on gold is only **7** rows; OOS recall 0.286. Score-threshold
  rejection is disabled.
- **CC BY-NC 4.0** (inherited from the Jina v5 base): non-commercial use only.
- Local transformers may warn about a Mistral tokenizer regex when loading
  this tokenizer; measured effect on this eval was ≤2/695 samples.

## Training

- Method: SetFit (contrastive body + linear `SetFitHead`)
- Base: `jinaai/jina-embeddings-v5-text-small-classification` (do **not**
  substitute the generic `jinaai/jina-embeddings-v5-text-small` repo; its
  `custom_st` wrapper runs under `torch.no_grad()`)
- Contract: max_length=**1024**, pooling=`lasttoken`, L2-normalize,
  padding_side=`left`
- Data: pool 7284 (5767 real + 1517 synthetic, min 150/class) → train 5237;
  body contrastive set capped at 250/label (4306 rows); head trained on the
  full 5237 split with **`balanced` class weights**
- Head: lr=5e-3, l2=0.01, 8 epochs; body: 2 epochs, 8 iterations, batch [2,64],
  AMP + gradient checkpointing
- Device: CUDA (NVIDIA GeForce RTX 5090); inference reference target is CPU
- Keyword mining: 62334 candidates → 20343 rejected for cross-label conflict,
  41105 for low support → **740 accepted**; disabled in the v5 policy after
  validation showed a −2.9pp routed drop, and stripped from the shipped
  policy file (training-corpus fingerprints)

## Files

| File | Purpose |
|---|---|
| `model.safetensors` + transformer/tokenizer configs | Fine-tuned embedding body (2.3 GB fp32) |
| `classification_head.pkl/.pt`, `model_head.pkl` | SetFit head (native) |
| `head_coef.npy`, `head_intercept.npy`, `head_meta.json` | Head arrays + metadata (labels, temperature, prompt prefix) |
| `encoder.onnx` + `encoder.onnx.data` | FP32 ONNX encoder (reference) |
| `encoder-woq8.onnx` + `encoder-woq8.onnx.data` | Block-wise int8 ONNX (recommended compact CPU path) |
| `ONNX_INFERENCE.md` | ONNX Runtime usage incl. prompt contract |
| `labels.json` | Labels, display names, intent→domain |
| `rejection_policy.json` | v5 routing policy (temperature 2.396; argmax-only, no keyword rules shipped) |
| `metrics.json` | Training-time evaluation record (`n_gold_test=695`) |
| `action_object_probe_metrics.json` | Probe metrics (n=45) |
| `release_manifest.json` | SHA256 + byte-size inventory of the reviewed inference-only release |

Not uploaded by default (local-only assets): training scripts, training and
evaluation datasets, `gold_test.jsonl`, `eval_proba_*.npy`, train logs, conflict
dumps, and dataset audits. The uploader uses a strict inference-only allowlist
and verifies `release_manifest.json` before upload.

## How this card was verified

- Closed-set + v5 routed metrics recomputed on CPU from the shipped checkpoint
  (`evaluate_model.py`, cached `eval_proba_695_*.npy`); matches `metrics.json`
  within 2/695 samples.
- Long-tail slice via `EVAL_SET_PATH=dataset_splits/eval_long_tail.jsonl`;
  coding OOD via `run_coding_eval.py` on the frozen `test_coding.jsonl`
  (leakage re-verified **0** against all release splits).
- Keyword-rule ablation (kw on/off) recomputed from cached probabilities;
  policy decisions from `routing_policy.decide_route` with the shipped
  `rejection_policy.json`.
- Calibration (raw vs T=2.396) computed from the same cached probabilities.
- Dataset counts from `dataset_splits/release_695/manifest.json`,
  `intent-router/dataset_audit.json`, and `intent-router/metrics.json`.
- The public release is generated with `python build_release_manifest.py --include-onnx`
  and the uploader verifies every selected inference artifact against that
  manifest. Training code and datasets are outside the uploader allowlist.

Demo URL: https://intent-api.sxueck.com/demo