import torch
from FlagEmbedding import BGEM3FlagModel, FlagReranker

print("=" * 50)
print("CUDA 诊断信息")
print("=" * 50)
print(f"PyTorch 版本: {torch.__version__}")
print(f"CUDA 是否可用: {torch.cuda.is_available()}")
print(f"CUDA 版本: {torch.version.cuda if torch.cuda.is_available() else 'N/A'}")
print(f"GPU 设备数量: {torch.cuda.device_count()}")
if torch.cuda.is_available():
    print(f"当前 GPU 设备: {torch.cuda.get_device_name(0)}")
    print(f"当前设备索引: {torch.cuda.current_device()}")
else:
    print("⚠️  CUDA 不可用！模型将使用 CPU 运行")
print("=" * 50)

# 测试 Embedding 模型
print("\n测试 Embedding 模型加载...")
try:
    model = BGEM3FlagModel(
        model_name_or_path=r'D:\Job_project\gitlet_ai_helper\app\model\bge-m3',
        device='cuda'
    )
    print(f"✅ Embedding 模型实际使用设备: {model.device}")
except Exception as e:
    print(f"❌ Embedding 模型加载失败: {e}")

# 测试 Reranker 模型
print("\n测试 Reranker 模型加载...")
try:
    reranker = FlagReranker(
        model_name_or_path=r'D:\Job_project\gitlet_ai_helper\app\model\bge-reranker-v2-m3',
        device='cuda',
        use_fp16=False
    )
    print(f"✅ Reranker 模型实际使用设备: {reranker.device}")
except Exception as e:
    print(f"❌ Reranker 模型加载失败: {e}")