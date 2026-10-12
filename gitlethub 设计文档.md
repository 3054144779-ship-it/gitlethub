# gitlethub 设计文档

## 通用约定

### 统一响应结构

所有成功响应的最外层结构一致：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

| 字段      | 类型           | 说明                           |
| --------- | -------------- | ------------------------------ |
| `code`    | int            | 业务状态码，`0` 表示成功       |
| `message` | string         | 状态描述，成功时为 `ok`        |
| `data`    | object / array | 实际业务数据，随接口不同而不同 |

### 错误响应

接口处理失败时返回标准 HTTP 错误，响应体为 FastAPI 默认结构（非 `ApiResponse` 包裹）：

```json
{
  "detail": "错误信息"
}
```

常见状态码：

| HTTP 状态码 | 含义         | 触发场景                                     |
| ----------- | ------------ | -------------------------------------------- |
| `400`       | 请求错误     | 订单状态不允许发起发货提醒                   |
| `404`       | 资源不存在   | 用户 / 订单 / 商品不存在，或订单暂无物流信息 |
| `409`       | 冲突         | 订单已存在进行中的退款申请                   |
| `422`       | 参数校验失败 | 请求体字段缺失或类型错误                     |
| `500`       | 服务器错误   | 数据库连接异常等                             |

### 时间与金额格式

| 类型 | 格式                       | 示例                    |
| ---- | -------------------------- | ----------------------- |
| 时间 | ISO 8601 字符串            | `"2026-08-31T10:00:00"` |
| 金额 | 十进制数字（保留两位小数） | `199.00`                |

---



```tex
当前project

gitlet/
├── Main.java
├── exception/
│   └── GitletException.java
├── utils/
│   └── Utils.java
── repository/
│   ├── Repository.java          
│   ├── RepositoryPaths.java     ← 路径常量（新增）
│   ── ObjectStore.java         ← 对象读写（新增）
├── commit/
│   └── Commit.java
├── stag/
│   └── Staging.java
├── handler/
│   ├── CommandHandler.java      ← 所有命令逻辑（从 Repository 搬出）
│   ├── BranchManager.java       ← 分支管理（从 Repository 搬出）
│   ├── MergeHandler.java        ← 合并逻辑（从 Repository 搬出）
│   └── StatusHandler.java       ← 状态查询（从 Repository 搬出）
└── api/
    ── GitletClient.java        ← 远程调用（后续新增）

gateway
|
|-- GatewayApplication.java
|
|-- controller 	
|   └── HealthController.java  # 心跳检测机制
|- 配置

ai 服务project

gitlet_ai_helper
|
|
|
|
|
|
```

![image-20260921140140754](C:\Users\ASUS\AppData\Roaming\Typora\typora-user-images\image-20260921140140754.png)



api:

```tex
CLI 命令
  │
  ├── 纯本地操作（直接操作文件系统，不走 API）
  │     ├── git init          → 创建 .gitlet 目录
  │     ├── git add           → 写暂存区
  │     ├── git commit        → 写本地 Objects
  │     ├── git log           → 读本地 Objects
  │     ├── git status        → 对比工作目录和暂存区
  │     ├── git branch        → 读本地 refs
  │     └── git merge         → 操作本地数据
  │
  ├── 远程操作（走 API 调 gateway）
  │     ├── git push          → POST /api/repos/{id}/push
  │     ├── git pull          → GET  /api/repos/{id}/pull
  │     ├── git clone         → GET  /api/repos/{id}/clone
  │     └── git remote add    → 记录远程地址
  │
  └── AI 操作（走 API 调 AI 服务）
        ├── git ai commit-msg → POST /api/ai/generate-commit-msg
        ├── git ai review     → POST /api/ai/code-review
        └── git ai explain    → POST /api/ai/explain-code
```

### push

```tex
POST /api/repos/{id}/push
```





### pull

```tex
GET  /api/repos/{id}/pull
```





### clone

```tex
GET  /api/repos/{id}/clone
```

```tex
gitlet_ai_helper/
├── app/
│   ├── main.py                    # 入口，启动 FastAPI + 消费 MQ
│   ├── config.py                  # 配置（API Key、Milvus 地址、MQ 地址等）
│   │
│   ├── router/                    # 【路由层】对标 Controller
│   │   ├── __init__.py
│   │   ├── review.py              # POST /api/ai/code-review
│   │   ├── commit_msg.py          # POST /api/ai/generate-commit-msg
│   │   ├── search.py              # POST /api/ai/semantic-search
│   │   └── archaeology.py         # POST /api/ai/archaeology
│   │
│   ├── service/                   # 【业务层】对标 Service
│   │   ├── __init__.py
│   │   ├── review_service.py      # 代码审查的业务编排
│   │   ├── commit_msg_service.py  # 生成提交信息
│   │   ├── search_service.py      # 语义搜索（含 Query 翻译）
│   │   └── archaeology_service.py # 代码考古（历史 RAG）
│   │
│   ├── repository/                # 【数据访问层】对标 Mapper
│   │   ├── __init__.py
│   │   ├── milvus_repo.py         # 向量库读写
│   │   ├── mysql_repo.py          # 业务元数据读写（如果 Python 直连 MySQL）
│   │   └── object_storage_repo.py # 从对象存储拉 Blob 内容
│   │
│   ├── client/                    # 【外部服务客户端】
│   │   ├── __init__.py
│   │   ├── llm_client.py          # 封装大模型 API（OpenAI、通义、DeepSeek等）
│   │   ├── embedding_client.py    # 封装 Embedding 模型（BGE-M3）
│   │   └── mq_consumer.py         # RabbitMQ 消费者
│   │
│   ├── schema/                    # 【数据模型层】Pydantic
│   │   ├── __init__.py
│   │   ├── request.py             # 请求体模型
│   │   ├── response.py            # 响应体模型
│   │   └── domain.py              # 领域模型（CodeChunk、ReviewResult 等）
│   │
│   ├── core/                      # 【核心工具层】
│   │   ├── __init__.py
│   │   ├── code_parser.py         # Tree-sitter AST 解析
│   │   ├── text_splitter.py       # Markdown/纯文本切分
│   │   ├── prompt_templates.py    # 所有 Prompt 模板集中管理
│   │   ├── file_type_router.py    # 按文件类型路由
│   │   └── logger.py              # 日志封装
│   │
│   └── worker/                    # 【异步任务消费者】
│       ├── __init__.py
│       ├── review_worker.py       # 消费 MQ 做代码审查
│       └── index_worker.py        # 消费 MQ 做 AST 切分 + 向量入库
│
├── tests/                         # 单元测试
├── requirements.txt               # 依赖
└── Dockerfile
```

