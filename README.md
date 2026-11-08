# GitletHub

一个自研的代码托管平台：Web 端管理仓库、提交、Pull Request 和 AI 代码评审，命令行客户端 `gitlet` 提供类 Git 的本地版本控制。

平台由四部分组成：Java 后端服务、Vue 3 前端、AI 评审编排服务，以及模型推理服务。

## 功能

- **仓库与代码浏览**：仓库列表、目录树、文件内容与语法高亮
- **版本控制**：分支、提交历史、diff 对比
- **Pull Request**：创建、评审、合并，支持在线解决冲突
- **AI 代码评审**：基于代码检索的评审建议
- **用户体系**：注册、登录、登出、个人资料与头像
- **命令行客户端**：20+ 个类 Git 命令，支持分支、合并、推送与拉取

## 系统架构

```
                     浏览器
                       │
                       ▼
        gitlethub-frontend（Nginx / Vite）
          /api/** 、/user/**
                       │
                       ▼
              gateway-service :8080
             ┌─────────┴──────────┐
             ▼                    ▼
   user-service :8081      remote-gitlet :8082
   /user/** /api/oss/**    /api/repos/** /api/ai/**
             │                    │
             │        ┌───────────┼──────────────┐
             ▼        ▼           ▼              ▼
          MySQL     Redis     RabbitMQ      gitlet-ai-helper :8000
                                  │         LangGraph + Milvus
                                  │              │
                                  └──────────────┤
                                                 ▼
                                    gitlet-ai-inference :8001
                                      embedding / rerank
                                                 │
                                            LiteLLM 网关
                                                 │
                                           模型供应商

gitlet CLI ──── HTTP ────> gateway-service /api/repos/**
```

后端服务通过 Nacos 注册与发现，网关按 `lb://` 负载均衡；AI 服务同样注册到 Nacos。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `gitlethub/` | Java 后端多模块工程 |
| `gitlethub/gateway-service` | Spring Cloud Gateway 网关，统一入口与鉴权 |
| `gitlethub/user-service` | 用户注册、登录、资料、OSS 头像预签名 |
| `gitlethub/remote-gitlet` | 仓库、分支、提交、PR、代码检索与 AI 评审接口 |
| `gitlethub/common-service` | 公共组件（JWT、Redis、响应封装等） |
| `gitlethub/gitlet_core` | `gitlet` 命令行客户端核心 |
| `gitlethub-frontend/` | Vue 3 Web 前端 |
| `gitlet-ai-helper/` | AI 评审编排：LangGraph、Milvus 检索、MQ 索引消费 |
| `gitlet-ai-inference/` | 模型推理：文本向量化与重排序 |

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.3、Spring Cloud Gateway、Nacos、Sentinel、MyBatis-Plus、MySQL、Redis、RabbitMQ、OpenFeign、Caffeine |
| 鉴权 | JWT（RS256）、Redis Token 黑名单 |
| AI 服务 | Python 3.12、FastAPI、LangChain / LangGraph、Milvus、BGE-M3、tree-sitter、aio-pika |
| 前端 | Vue 3、Vite、Element Plus、Pinia、Axios |
| 部署 | Docker、Docker Compose、Nginx |

## 快速开始

### 1. 准备基础设施

需要提前启动：

- MySQL、Redis、RabbitMQ
- Nacos（服务注册与配置）
- Milvus（AI 服务使用）
- LiteLLM（模型调用统一网关）

各服务的连接信息通过环境变量注入，默认值见对应模块的 `src/main/resources/application.yaml`。

### 2. 启动后端

```bash
cd gitlethub

# 先安装公共模块到本地仓库
./mvnw install -DskipTests

# 分别启动三个服务（Windows 下用 mvnw.cmd）
./mvnw -pl user-service spring-boot:run
./mvnw -pl remote-gitlet spring-boot:run
./mvnw -pl gateway-service spring-boot:run
```

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `NACOS_SERVER_ADDR` / `NACOS_USERNAME` / `NACOS_PASSWORD` / `NACOS_NAMESPACE` | Nacos 连接 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | Redis 连接 |
| `ALIYUN_OSS_ACCESS_KEY_ID` / `ALIYUN_OSS_ACCESS_KEY_SECRET` | 头像直传所需 OSS 凭证 |
| `SENTINEL_DASHBOARD` | Sentinel 控制台地址 |

### 3. 启动 AI 服务

```bash
cd gitlet-ai-inference
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
# 按需创建 .env，至少配置 LITELLM_BASE、LITELLM_MASTER_KEY、EMBED_MODEL、RERANK_MODEL、NACOS_*
python -m uvicorn main:app --app-dir app --host 0.0.0.0 --port 8001
```

```bash
cd gitlet-ai-helper
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
# 按需创建 .env，至少配置 MESSAGE_QUEUE_URL、PR_MERGED_QUEUE、MILVUS_URL、LLM_MODEL、LITELLM_MASTER_KEY、NACOS_*
# 需要本地创建 config.yaml：包含 Milvus collection、模型与 LLM 参数（该文件不入库）
python -m uvicorn main:app --app-dir app --host 0.0.0.0 --port 8000
```

两个服务都注册到 Nacos，服务名分别为 `gitlet-ai-helper`、`gitlet-ai-inference`。

### 4. 启动前端

```bash
cd gitlethub-frontend
npm ci
npm run dev
```

打开 <http://localhost:5173>。开发服务器默认把 `/api`、`/user` 代理到网关，可用 `VITE_GATEWAY_TARGET` 覆盖。

生产环境可使用目录内的 `Dockerfile` 与 `docker-compose.yml` 构建 Nginx 镜像，`GATEWAY_UPSTREAM` 指定网关地址。

## 命令行客户端

发布产物为 `gitlet.jar` 与 Windows 安装包 `gitlet-1.0.msi`；源码位于 `gitlethub/gitlet_core`，入口类 `gitlet.Main`，也可在 IDE 中直接运行。

基本流程：

```bash
mkdir my-project && cd my-project

gitlet init                                              # 初始化，默认分支 master
gitlet remote add http://<平台地址>/api/repos/<用户>/<仓库>
gitlet login                                             # 输入平台账号密码

gitlet add .
gitlet commit "第一次提交"
gitlet push origin
```

> 先 `remote add` 再 `login`：登录时会使用已绑定的仓库地址请求平台接口。

常用命令：

| 命令 | 说明 |
| --- | --- |
| `gitlet init` | 初始化仓库 |
| `gitlet add <文件/目录/.>` | 添加到暂存区 |
| `gitlet commit "<信息>"` | 提交暂存区 |
| `gitlet status` | 查看工作区与暂存区状态 |
| `gitlet log` / `global-log` / `find` | 查看与查找提交历史 |
| `gitlet branch` / `checkout` / `rm-branch` | 分支管理 |
| `gitlet merge <分支>` | 合并分支 |
| `gitlet reset <提交 ID>` | 回退到指定提交 |
| `gitlet remote add [别名] <地址>` | 绑定远程仓库 |
| `gitlet login` / `logout` | 登录 / 登出 |
| `gitlet push [别名] [分支]` | 推送 |
| `gitlet pull [别名] [分支]` | 拉取 |

## 配置与安全

- `.env`、`config.yaml` 以及本地部署产物已被 `.gitignore` 忽略，请勿提交真实密钥。
- `config.yaml` 中的 LiteLLM master key 与基础设施地址应在部署时挂载或通过环境变量注入。
- 网关负责 JWT 验签与黑名单校验，并在转发前移除客户端自带的 `X-User-Id`，防止伪造身份。
- 合并 Pull Request 仅限仓库 OWNER。
