# ONNX Inference

Inputs: `input_ids` and `attention_mask`, both int64 `[batch, sequence]`.
Output: `sentence_embedding` float32 `[batch, 1024]`.

The exported model preserves pooling mode(s) `lasttoken` and
L2 normalization `True`. Use `max_length=1024` unless you
intentionally choose another sequence length supported by the tokenizer/model.

**Prompt prefix**: this base model applies the prompt prefix `'Document: '`
to every text inside `SentenceTransformer.encode()` (plain string prepending).
Prepend the same prefix before tokenizing or embeddings will drift from the
SetFit-native path.

```python
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

model_dir = Path("intent-router")
meta = json.loads((model_dir / "head_meta.json").read_text(encoding="utf-8"))
policy = json.loads((model_dir / "rejection_policy.json").read_text(encoding="utf-8"))
tok = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
tok.padding_side = "left"
# Use encoder.onnx for latency-sensitive CPU serving. Select encoder-woq8.onnx
# only where the 3.9x smaller footprint outweighs its higher CPU latency.
sess = ort.InferenceSession(str(model_dir / "encoder.onnx"), providers=["CPUExecutionProvider"])
coef = np.load(model_dir / "head_coef.npy")
intercept = np.load(model_dir / "head_intercept.npy")
labels = meta["labels"]
prompt_prefix = meta.get("prompt_prefix", "")
temperature = float(policy["temperature"])

enc = tok([prompt_prefix + "your text"], padding=True, truncation=True, max_length=1024, return_tensors="np")
embedding = sess.run(None, {"input_ids": enc["input_ids"].astype("int64"), "attention_mask": enc["attention_mask"].astype("int64")})[0]
logits = (embedding @ coef.T + intercept) / temperature
probs = np.exp(logits - logits.max(axis=-1, keepdims=True))
probs /= probs.sum(axis=-1, keepdims=True)
prediction = labels[int(probs.argmax(axis=-1)[0])]
```
