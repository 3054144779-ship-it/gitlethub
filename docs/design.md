# gitlethub 设计文档

| | |
| --- | --- |
| 状态 | draft |
| Owner | 花火 |
| Reviewer | 待指派 |
| 最后更新 | 2026-09-21 |
| 接口冻结 | 未冻结（`docs/openapi.yaml` 定稿后填日期） |
| 取代 | `gitlethub 设计文档.md`（无状态草稿，保留作参考） |

**怎么读这份文档**

- 标记 `(临时)` 的数值和结论都是初稿建议，**可以直接用，也可以改**，改完把标记去掉。
- 全文**唯一需要你填的地方是 [附录 A：待决策清单](#附录-a待决策清单)**。正文里出现的 `→ D7` 表示这一项挂在那个决策上。
- 仓库接口的定义不在这份文件里，在 `docs/openapi.yaml`（只含 `/api/repos/**`）。
  AI 接口的契约在 `gitlet_ai_helper` 那个 project 里，不在这里。

---

## 1. 背景

gitlet 是一个类 Git 版本控制系统的子集（CMU-61B 风格），当前形态是单进程 CLI：

- 仓库状态全部落在工作目录下的 `.gitlet/` 目录，磁盘是唯一的跨命令状态载体。
- 执行模型是**一条命令一个 JVM 进程**（`java gitlet.Main <command>`）。进程内的对象随进程结束消失。
- 因此「当前操作哪个仓库」由**进程的工作目录**决定，而不是由参数决定。

现在要把它扩展成云端代码托管平台：一个服务进程同时服务多个用户、多个仓库，并提供 AI 辅助能力。

核心已做过一轮改造（M0），把「仓库」从进程级全局变成可多实例：

| 类 | 行数 | 职责 |
| --- | --- | --- |
| `repository/Repository.java` | 92 | 门面：`repoId` / `paths` / `objects` / 懒加载的 `stage` |
| `repository/RepositoryPaths.java` | 43 | 由 `(repoId, cwd)` 算出的 7 个磁盘路径 |
| `repository/ObjectStore.java` | 178 | object 读写、SHA-1 补全、commit 持久化 |
| `handler/CommandHandler.java` | 337 | init/add/commit/rm/log/global-log/find/checkout*/reset |
| `handler/MergeHandler.java` | 311 | merge / LCA / 三路合并 / 冲突生成 |
| `handler/StatusHandler.java` | 207 | status 及各类派生查询 |
| `handler/BranchManager.java` | 114 | 分支增删查 + HEAD 指针 |
| `Main.java` | 173 | CLI 入口，组装对象图 |
| `commit/Commit.java` | 45 | 纯数据对象（Lombok `@Data`） |
| `stag/Staging.java` | 52 | 暂存区：`stagingMap` + `removeSet` |
| `utils/Utils.java` | 231 | 序列化 / SHA-1 / 文件读写 |

服务端复用核心的入口就是这四个构造：

```java
Repository     repo     = new Repository(String repoId, File cwd);
RepositoryPaths paths   = repo.getPaths();     // getCwd/getGitletDir/getObject/getBranchDir/getHeadFile/getCommits/getStagingFile
ObjectStore    objects  = repo.getObjects();
CommandHandler commands = new CommandHandler(repo);   // 四个 Handler 均只依赖 Repository
```

`repoId` 目前**只作标识/诊断用，不参与路径计算**，路径一律由调用方传入的 `cwd` 决定。

---

## 2. 目标 / 非目标

`(临时)` 以下都是初稿建议。**同意的留着，不同意的直接删。** → D1

### 目标

- 一个仓库 = 一个独立实例，同一进程内多个仓库互不干扰。**（已完成）**
- 用户可以把本地 gitlet 仓库推送到云端、从云端拉取。
- 提供 AI 辅助：生成提交信息 / 代码审查 / 解释代码。

### 非目标

- 不实现 Git 线协议（smart HTTP / packfile），不追求与真 git 互通。
- 不做 Web UI，交互一律走 CLI。
- 不做分布式部署，先单机单盘。
- 不做细粒度权限模型，先只区分 owner / 非 owner。
- 不做断点续传、增量打包等传输优化。
- 不做仓库可视化、issue、PR、CI 等，只做托管 + AI。

> 非目标比目标重要：它是防止范围蔓延的唯一手段。

---

## 3. 规模与约束

`(临时)` 数字全部是初稿建议，见 → D2、D3、D4。

| 约束 | 取值 `(临时)` | 超出时怎么办 |
| --- | --- | --- |
| 单仓库体积上限 | 100 MB | push 返回 413 |
| 单文件体积上限 | 10 MB | push 返回 413 |
| 单次 push 体积 / 超时 | 50 MB / 30 s | 返回 413 / 客户端分批 |
| 目标用户数 | 100 | — |
| 目标仓库数 | 1000 | — |
| 仓库落盘路径 | `$REPOS_ROOT/<owner>/<repo>` | → D3 |
| 部署形态 | 单机，非容器 | → D2 |

已定的技术约束：

- Java 17（`gitlet_core` 用 `maven.compiler.release=17`）。
- 父 pom `spring-boot-starter-parent:3.3.0`，依赖版本统一由它的 BOM 管理。
- `gitlet_core` 是**纯库**：除 Lombok（`provided`）外无任何依赖，不依赖 Spring。
- 目录命名 `(临时)`：现在是 `gitlet_core`（下划线），但同级的 `gateway-service` / `remote-gitlet` 都是连字符，Maven 惯例也是连字符。改名要动父 pom 的 `<modules>` 和 IDE 配置。→ D4

---

## 4. 架构

```
gitlethub (parent, packaging=pom)
├── gitlet_core/       纯库，无 Spring。CLI 与 remote-gitlet 依赖它
├── gateway-service/   Spring Cloud Gateway：路由 + 鉴权过滤 + 限流
└── remote-gitlet/     实现 /api/repos/**，依赖 gitlet_core

gitlet_ai_helper        AI 服务，独立 project，不在本仓库 → D15
```

**职责与依赖方向（单向，不可逆）**

| 模块 | 职责 | 依赖 `gitlet_core` |
| --- | --- | --- |
| `gitlet_core` | 仓库操作核心 + CLI（含 `api/GitletClient` 远程客户端） | — |
| `gateway-service` | 唯一入口。路由、鉴权、限流、日志过滤，**不含业务接口** | ❌ 不得依赖 |
| `remote-gitlet` | 仓库与对象传输的服务端实现 | ✅ |
| `gitlet_ai_helper` | AI 三类接口。独立 project，契约跟着那个项目走 | ❌ |

`gateway-service` 是**策略层不是业务层**：它不该出现任何 `/api/repos/**` 的 controller。
把业务 spec 生成到 gateway 是错的——生成目标是 `remote-gitlet`。

### 当前落地状态

| 模块 | 磁盘 | 已加入父 pom `<modules>` | 状态 |
| --- | --- | --- | --- |
| `gitlet_core` | ✅ | ✅ | 1802 行，21 个集成测试全绿 |
| `gateway-service` | ✅ | ❌ | 4 个文件，`application.yaml` 无路由，**尚不可用**（见 §10） |
| `remote-gitlet` | ✅（仅 pom） | ❌ | 空壳，接口契约已定（见 §6） |

父 pom 目前只注册了 `gitlet_core`，所以 `mvn validate` 看不到另外两个模块——它们的问题因此一直被掩盖。

### 进程与并发模型

- **CLI 路径**：一个进程服务一个仓库，`cwd` 即仓库位置。**（已完成）**
- **服务端路径**：一个进程服务多个仓库，需要处理：
  - `Repository` 把 `Staging` 缓存在内存里（懒加载），这只在「一仓库一实例 + 单线程」下正确。→ D12
  - 同一仓库被并发操作时，按 `repoId` 加锁还是每请求新建实例。→ D12
  - 服务端是否缓存 `Repository` 实例。→ D13

---

## 5. 核心契约（头号阻塞点）

当前 `gitlet_core` 的仓库操作和 CLI 输出是**同一套代码**：

- `Main.java` 里有 4 处 `System.exit(0)`：空命令、空提交信息、`checkout` 格式错误、`.gitlet` 不存在。
- 输出全部直接写 `System.out.println`，集中在 `Repository.outputCommitLog()`、`StatusHandler.printSection()`、`CommandHandler.find()`。
- 错误信息通过 `Utils.message()` 打印，不抛异常、不返回状态。

**后果**：这套代码无法在服务进程里运行——一个 `System.exit` 就会杀掉整个服务。

建议的改造形状 `(临时)`：

```java
// 核心只返回结果对象，不打印、不退出；stdout 渲染属于 CLI 层
interface GitletApi {
    CommitResult commit(String repoId, String message);
    StatusView   status(String repoId);
    MergeResult  merge(String repoId, String branch);
}

// 渲染层：CLI 实现打到 stdout，服务端实现转成 DTO
interface Renderer {
    void commitLog(Commit c, String sha1);
    void message(String text);
}
```

服务端侧不实现 `Renderer`：`remote-gitlet` 的 controller 把结果对象包成 §6 的
`{code, message, data}` 信封，CLI 侧用 `Renderer` 打 stdout。**核心对两种渲染都不感知。**

| 决策 | ID |
| --- | --- |
| 错误表达方式：异常 vs `Result` 对象 | → D6 |
| `System.exit` 归谁（建议：只留在 `Main`） | → D7 |
| 渲染：接口注入 vs 直接返回字符串 | → D8 |

---

## 6. 接口

**契约正文在 [`docs/openapi.yaml`](openapi.yaml)，本节只做索引用。** 改接口改那个文件。

这份 spec 只描述 **`remote-gitlet`** 实现的 `/api/repos/**`。校验和生成：

```bash
npx @redocly/cli lint docs/openapi.yaml
openapi-generator generate -i docs/openapi.yaml -g spring -o remote-gitlet
```

**spec 覆盖的（实现方 `remote-gitlet`）**

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/repos/{owner}/{repo}/objects` | push 第一步：上传对象 |
| GET | `/api/repos/{owner}/{repo}/objects?since=` | pull / clone：拉取对象 |
| POST | `/api/repos/{owner}/{repo}/refs/{branch}` | push 第二步：更新分支（乐观锁） |
| GET | `/api/repos/{owner}/{repo}/refs/{branch}` | 读取分支指向 |

**不在 spec 里的**

| 方法 | 路径 | 实现方 | 契约在哪 |
| --- | --- | --- | --- |
| POST | `/api/ai/generate-commit-msg` | `gitlet_ai_helper` | 那个 project 自己维护 |
| POST | `/api/ai/code-review` | `gitlet_ai_helper` | 同上 |
| POST | `/api/ai/explain-code` | `gitlet_ai_helper` | 同上 |
| GET | `/health` | `gateway-service` | 不需要契约文件 |

**统一响应结构**：所有响应（含错误）都是 `{code, message, data}`。`code` 是**业务码段**，
与 HTTP 状态码解耦；HTTP 状态码仍保留语义给网关和通用中间件用，但**客户端判断分支只看 `code`**。

码段划分（完整注册表在 `openapi.yaml` 的 `ApiResponse`）：

| 段 | 范围 | 用途 |
| --- | --- | --- |
| — | `0` | 成功 |
| 请求与内容 | `1xxxx` | 格式非法、内容校验失败、超限、限流 |
| 认证授权 | `2xxxx` | 未认证、无权限 |
| 仓库与分支 | `3xxxx` | 仓库不存在、分支不存在 |
| 并发冲突 | `5xxxx` | 乐观锁失败 |
| 系统 | `9xxxx` | 服务端错误 |

错误详情放 `data`：`50001` 带 `currentSha`，`10003` 带 `missingSha`。

**gateway 承担的策略**（不在 spec 里，因为不产生业务 controller）：

| 策略 | 行为 |
| --- | --- |
| 鉴权过滤 | 校验 Bearer JWT，失败直接返回 `401` + `code 20001`，不透传到后端 |
| 限流 | 超阈值返回 `429` + `code 10005` |
| 日志过滤 | 记录 method / path / 耗时 / 状态码 |

> 请求到达 `remote-gitlet` 时**已被假定通过鉴权**。它自己不再校验 token，只校验 owner 归属（`403` / `code 20002`）。

| 决策 | ID |
| --- | --- |
| 鉴权：JWT 怎么签发、密钥放哪 | → D9 |
| 限流阈值与维度（按 IP / 按用户 / 按仓库） | → D19 |
| AI 服务：独立进程 vs 同进程；是否流式；喂 diff 还是整份文件 | → D15 |

---

## 7. 数据模型

`(临时)` 见 → D10、D11。

| 数据 | 存放位置 `(临时)` | 说明 |
| --- | --- | --- |
| object 内容（blob + commit） | 磁盘 `.gitlet/Objects/` | 本地与远端一致；远端根路径 → D3 |
| 分支引用（refs） | 磁盘，与本地结构一致 | → D10 |
| 用户 / 仓库名 / owner | 数据库（r2dbc） | → D11 |
| 权限 | 数据库 | → D11 |
| 每个 remote 的同步进度 | 数据库 | push 增量计算依赖它 |
| AI 调用记录 / 配额 | 暂不落库 | → D11 |

> `Commit` 是 Java 序列化对象（`serialVersionUID = 1L`），不是文本。服务端**必须原样落盘**，不能反序列化再重新序列化，否则 SHA-1 会变、所有引用失效。

---

## 8. 关键流程

### push

```
1. 客户端对比 remote 记录的 sha 与本地 refs，算出服务端缺失的 object 集合
2. POST /objects            → 幂等；仓库不存在则自动创建
3. POST /refs/{branch}      → 带 expectedOldSha 做乐观锁；分支不存在则新建
4. 服务端校验并更新引用
```

- **没有单独的开仓接口**：第 2 步是唯一会建仓库的地方（含至少一个对象才建）。
  第 3 步要求仓库已存在，否则返回 `30001`。
- 第 2 步成功、第 3 步失败的处理 → D14
- 自动建仓的滥用风险 → D21
- 建仓要**同时写数据库（仓库元数据）和磁盘目录**，中间失败会留半状态 → D22

### pull / clone

```
1. GET /refs/{branch}                 → 拿到远端最新 sha
2. GET /objects?since=<本地最新 sha>   → 拉缺失的 object，落盘
3. 更新本地 remote 记录，checkout
```

### 并发

| 场景 | 处理方式 `(临时)` | ID |
| --- | --- | --- |
| 两个客户端同时 push 同一分支 | 乐观锁（`expectedOldSha`），`50001` 后 pull 重试 | — |
| 同一仓库的并发读写 | 按 `repoId` 加锁 | → D12 |
| 服务端 `Repository` 实例是否复用 | 不缓存，每请求新建 | → D13 |

---

## 9. 被否决的方案

| 方案 | 否决理由 |
| --- | --- |
| 用 JGit / libgit2 取代自研核心 | 能直接白嫖 Git 协议和生态，但项目的核心价值就是「自己实现一个 git」，换掉等于没有作品。而且 JGit 是标准 Git 格式，和这里自研的 `.gitlet` 格式不兼容，等于换掉整个数据层。 |
| objects 打包成 packfile 上传 | 能把 push 的往返次数从 N 降到 1，但需要实现增量压缩和索引，复杂度远超当前规模所能受益。等单仓库真的到 GB 级再说。 |
| objects 存对象存储（S3 / MinIO） | 更易扩容、天然多副本，但本地调试要先起 MinIO，且 `ObjectStore` 现在全是本地文件 API，抽象一层要动整个核心。单机磁盘够用。 |
| 把 refs 也放进数据库 | 查询方便，但会让远端和本地的存储结构分叉，同一个核心代码要维护两种 refs 读取路径。放磁盘能直接复用 `BranchManager`。 |
| 用 REST 之外的自定义二进制协议 | 省流量，但丢掉 OpenAPI 带来的代码生成、文档、调试（curl / Postman）全部便利。 |

> 这一节比「做什么」更值得维护：半年后你不会记得为什么当时没选 JGit。

---

## 10. 风险 / 未决问题

### 阻塞级（不解决就做不下去）

| # | 问题 | 影响 |
| --- | --- | --- |
| 1 | `gitlet_core` 用 `System.exit` + `System.out` 渲染，无法跑在服务端 | 服务端无法复用核心，只能重写逻辑。见 §5 |
| 2 | `gateway-service` 同时引入 `spring-boot-starter-web`（Servlet）和 `spring-cloud-starter-gateway`（WebFlux） | 二者互斥，Spring Cloud Gateway 要求 classpath 上没有 Spring MVC，启动即失败 |
| 3 | `gateway-service` 的两个 spring-cloud 依赖没有 BOM 管版本 | 父 pom 只继承了 `spring-boot-starter-parent`，缺 `spring-cloud-dependencies` 和 Alibaba 的 BOM，依赖无法解析 |
| 4 | `gateway-service` / `remote-gitlet` 未注册进父 pom `<modules>` | 从不参与构建，上述问题无人发现 |

> 2 和 3 怎么解 → D16

### 待解决

| # | 问题 | 影响 |
| --- | --- | --- |
| 5 | `HealthController.healthCheck()` 没有方法级 `@RequestMapping`，且用 `@Controller` 返回 String（会被当视图名）；`import javax.xml.transform.Result` 是误导入 | 健康检查打不通 |
| 6 | `gateway-service` 的 `application.yaml` 空的，一条路由都没配 | 所有请求无处转发 |
| 7 | `gateway-service` 用包名 `com.hanabi`，与父 groupId `com.huahuo` 不一致 | 坐标混乱 |
| 8 | gateway 的鉴权 / 限流 / 日志过滤都还没实现 | 安全 → D9、D19 |
| 9 | 自动创建仓库意味着任何认证用户都能无限建仓，配额 / 频率限制没定 | 滥用风险 → D21 |
| 10 | `remote-gitlet` 只有 pom，`/api/repos/**` 一个接口都没实现 | 见 §11 的 M2 / M3 |
| 11 | `Repository` 的 `Staging` 内存缓存只在单线程下成立 | 并发正确性 → D12 |
| 12 | 模块命名 `gitlet_core` vs `gitlet-core` 未统一 | 可读性 → D4 |
| 13 | `gitlet/dump/`（`DumpObj`、`Dumpable`）疑似死代码 | 待确认后删除 |

---

## 11. 里程碑与验收标准

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 核心静态 → 实例化，一个仓库一个实例 | **已完成** |
| M1 | 核心去 `System.out` / `System.exit`，暴露 §5 的契约 | 未开始 |
| M2 | 修好 `gateway-service`（依赖 + 路由 + 鉴权/限流），按 spec 生成 `remote-gitlet` 骨架并跑通 `/health` 和第一个真实接口 | 未开始 |
| M3 | push / pull 全链路 | 未开始 |
| M4 | AI 服务接入（独立 project） | 未开始 |

### M1 验收标准

- **Given** 一个已初始化的仓库，**When** 依次执行 `init` / `add` / `commit` / `log` / `status` / `checkout` / `branch` / `merge` / `reset`，**Then** stdout 与改造前逐字节一致，且 `make check` 21/21 全绿。
- **Given** 服务端调用同一套核心，**When** 执行任意命令，**Then** 进程不退出（无 `System.exit`）。

### M2 验收标准

- **Given** `gateway-service` 启动（依赖修好、路由配好），**When** `GET /health`，**Then** 返回 `200`。
- **Given** 经 gateway 访问 `POST /api/repos/{owner}/{repo}/objects` 传空 `objects` 数组，
  **Then** 返回 `200 {code:0, message:"ok", data:{received:[], missing:[]}}` 而不是 500。
- **Given** 请求不带 token，**When** 打 `/api/repos/**`，**Then** gateway 直接返回 `401` / `code 20001`，且后端日志里看不到这次请求。
- **Given** 请求带合法 token 但访问别人的仓库，**Then** `403` 且 `code` 为 `20002`（由 remote-gitlet 判定）。
- **Given** 一个从未 push 过的 owner/repo，**When** 第一次 `POST /objects`，**Then** 自动创建仓库并返回 `200`。

### M3 验收标准

- **Given** 两个客户端持有同一分支的同一 `expectedOldSha`，**When** 先后 push，
  **Then** 第一个 `200`，第二个 `409` 且响应体是 `{code:50001, data:{currentSha:"..."}}`。
- **Given** 一个本地仓库，**When** push 到远端再 clone 到空目录，**Then** 两次 `log` 输出一致。

---

## 12. 验证基线

每个里程碑都必须保持（**已完成**）：

```bash
cd gitlet_core/src/test/gitlet_test
make compile     # 编译核心
make check       # 21/21 必须全绿
```

改动 Makefile 时不要破坏这两条环境依赖：

1. Windows 上 javac 的 `-cp` 分隔符是 `;`，且 classpath 必须用 Windows 形式路径（`cygpath -w`）。用 `:` 或 POSIX 路径会让 Lombok 静默失效，报出一堆 `找不到符号 getXxx()`，看起来像源码 bug。
2. `PYTHON = python3 -X utf8`。`.in` 测试文件是 UTF-8，但 Python 在这个中文 locale 下默认用 cp936 读，不加 `-X utf8` 会全部报 `FAILED (gbk)`。

服务端侧（未开始）：

| 层次 | 方式 |
| --- | --- |
| 契约 | `npx @redocly/cli lint docs/openapi.yaml`（改接口后必跑） |
| 骨架 | `openapi-generator ... -o remote-gitlet`，生成物只当接口签名用，实现手写 |
| 单元测试 | `remote-gitlet` 侧 Spring Boot Test，覆盖 controller 与 `ObjectStore` 边界 |
| 策略 | gateway 的鉴权 filter / 限流单独测：无 token、过期 token、超阈值 |
| 端到端 | 起 gateway + remote-gitlet，用真实 CLI 跑 init → push → clone → pull |
| 并发 | 按 M3 的验收标准做双客户端冲突用例 |

---

## 附录 A：待决策清单

**这是全文唯一需要你填的地方。** 右边一列写你的决定；同意建议就写「同建议」，不同意的写清楚选择。每行前面的 ID 在正文里被引用。

| ID | 待决策 | 建议 `(临时)` | 你的决定 |
| --- | --- | --- | --- |
| D1 | 非目标清单是否就这 6 条 | 见 §2，删改随意 | |
| D2 | 规模数字（仓库/文件/超时/用户数/部署形态） | 见 §3 表格 | |
| D3 | 服务端仓库落盘路径 | `$REPOS_ROOT/<owner>/<repo>` | |
| D4 | 模块命名：`gitlet_core` 还是 `gitlet-core` | 改成 `gitlet-core`，与同级一致 | |
| D5 | 哪些命令走远程（`push`/`pull`/`clone`/`remote add`？） | 就这四个，其余全本地 | |
| D6 | 核心的错误表达：抛异常还是返回 `Result` | 抛异常，`GitletException` 已存在 | |
| D7 | `System.exit` 归谁 | 只留在 `Main`，核心一律不调 | |
| D8 | 渲染方式：接口注入还是返回字符串 | 返回结果对象，字符串由 CLI 拼 | |
| D9 | 鉴权：token 怎么签发和校验 | Bearer JWT，先硬编码一个走通链路 | |
| D10 | refs 存磁盘还是数据库 | 磁盘，复用 `BranchManager` | |
| D11 | 用户 / 权限 / 同步进度存哪 | r2dbc 数据库 | |
| D12 | 同一仓库并发：加锁还是每请求新建 | 每请求新建 `Repository`，不加锁 | |
| D13 | 服务端是否缓存 `Repository` 实例 | 不缓存 | |
| D14 | push 第 2 步失败（对象已传、refs 没更新）怎么恢复 | 无害，重试即可，objects 上传幂等 | |
| D15 | AI 服务形态：进程模型；流式 / 非流式；喂 diff / 整份文件 | 独立 project `gitlet_ai_helper`（**已定**）；其余建议同进程外的独立服务、非流式、先喂 diff | |
| D16 | `gateway-service` 的依赖冲突怎么解 | 去掉 `spring-boot-starter-web`，补 spring-cloud BOM | |
| D17 | `remote-gitlet` 负责什么 | 实现 `/api/repos/**`，服务端仓库存储 + 对象传输（**已定**） | |
| D18 | `code` 的取值语义 | 业务码段（**已定**）：`0` 成功，`1xxxx` 请求内容，`2xxxx` 认证授权，`3xxxx` 仓库分支，`5xxxx` 并发冲突，`9xxxx` 系统 | |
| D19 | 限流阈值与维度 | 先按 token 维度，60 req/min；超了 `10005` | |
| D20 | `/health` 是否也包统一信封 | 不包，探针只需要 `200`；业务接口一律包 | |
| D21 | 自动创建仓库的配额 / 频率限制 | 先按 owner 限 50 个仓库，超了走 `20002` 或另开 `30003` | |
| D22 | 自动建仓时数据库与磁盘的一致性 | 先建目录后写库，库里没记录就当仓库不存在（可被下次 push 覆盖） | |
