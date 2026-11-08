import torch
import time
from FlagEmbedding import BGEM3FlagModel

model = BGEM3FlagModel(
    model_name_or_path=r'D:\Job_project\gitlet_ai_helper\app\model\bge-m3',
    device='cuda',
    use_fp16=True
)

print(f"CUDA 可用: {torch.cuda.is_available()}")
print(f"当前设备: {torch.cuda.current_device() if torch.cuda.is_available() else 'cpu'}")

# 用长文本 + 大 batch 让 GPU 真正工作
long_text = "这是一个测试代码文件。" * 500
docs = [long_text] * 512

# 先预热一次
print("预热中...")
_ = model.encode(docs[:4], return_dense=True, return_sparse=True)
torch.cuda.synchronize()

# 正式测试
print("开始 GPU 推理...")
start = time.time()
result = model.encode(docs, batch_size=256, return_dense=True, return_sparse=True)
torch.cuda.synchronize()
elapsed = time.time() - start
print(f"GPU 处理 512 条长文本: {elapsed:.3f}s")

# 显示 GPU 内存使用
print(f"GPU 显存占用: {torch.cuda.memory_allocated() / 1024**2:.1f} MB")
print(f"GPU 显存峰值: {torch.cuda.max_memory_allocated() / 1024**2:.1f} MB")
