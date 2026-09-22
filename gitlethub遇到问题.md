问题：

JVM 加载 Repository 类 → loadStaging () 执行 → .gitlet 不存在 → 返回空 Staging 

用户执行 init () → 创建了 .gitlet/ 和 staging 文件 

用户执行 add () → getStaging () 返回的还是第 1 步的空对象 → 和磁盘上的不一致

解决

懒加载，每次调用都执行 `loadStaging()` 从磁盘读取最新状态。

问题：

```tex
当前（CLI 模式）：
  用户执行命令 → 启动 JVM → 执行 → JVM 退出
  用户再执行   → 启动 JVM → 执行 → JVM 退出
  ...每次都是新进程

云端（Server 模式）：
  启动 JVM（Spring Boot）→ 一直运行
  用户发 HTTP 请求 → 处理 → 返回响应
  用户再发请求     → 处理 → 返回响应
  ...同一个进程，持续服务
```

最大的问题：你所有 Handler 都是 static 方法 + static 的 stage。如果两个用户同时操作不同仓库，他们的 stage 会互相覆盖。



问题

本地暂存区咋办？远程的有多个仓库，本地的呢？

```tex
远程服务器（多个仓库）                    用户电脑（多个本地目录）
┌──────────────────────┐              ┌──────────────────────────┐
│  repo-A (id: 001)     │              │  ~/projects/project-A/    │
│  repo-B (id: 002)     │              │    .gitlet/               │
│  repo-C (id: 003)     │              │    工作目录/               │
└──────────────────────┘              │                           │
                                      │  ~/projects/project-B/    │
                                      │    .gitlet/               │
                                      │    工作目录/               │
                                      └──────────────────────────┘
```

解决：

每个本地仓库有自己独立的暂存区，互不干扰。

用仓库名+当前文件夹名创建仓库

用户在哪个目录下执行命令，就用哪个目录下的 .gitlet/



问题：

为什么核心代码不用 Spring IoC，只在边缘用？

  不用 IoC 的理由：

  1. Repository 是数据，不是协作者。 IoC 容器管道是固定的、启动时就确定的长生命周期单例（DataSource、Service）。而 Repository 是按请求参数现造的——只有请求进来时你才知道要开 alice/notes 还是 bob/notes。容器没法 `@Autowired` 一个取决于运行时参数的对象。Spring 里对应的正确形状是 @Component RepositoryFactory factory.open(repoId, path) 返回 Repository。也就是说 IoC 能帮你拿到 factory，但拿不到 Repository 本身——这次改造 90% 的接线它管不了。
    
  2. 如果 Handler 做成单例 @Component，就正好把刚拆掉的坑请回来了。它们会变回进程级共享状态，一并发服务多个仓库就串。要避开就得 @Scope("prototype") 或 request scope，那自动装配的收益基本归零——每个请求还是得手动拿 bean。
    
  3. CLI 不想要容器。 Main.main 是被 java gitlet.Main add f.txt 拉起来的裸 JVM，一条命令一个新进程。Handler 全变 bean的话，add 一个文件也得先 SpringApplication.run()：5ms → ~1s，还要给 tester 的 classpath 加一堆东西。而现在 21 个用例就是靠逐个 java gitlet.Main 跑的。
    
  4. 手写接线只有 5 行，依赖是一棵无环静态树。为 5 行引容器是负收益。

  正确的切分是：核心保持无容器，边缘用 IoC。 gitlet/ 是个纯库——CLI 能 new，Spring 也能 new。将来网关那层才是 IoC
  该出现的地方：@RestController 注入 RepositoryFactory bean，每请求 factory.open(...)。这跟你设计文档里
  api/GitletClient.java 的定位是一致的，而且是纯增量，不用再动核心。

简单来说就是：
```tex
Spring IoC 适合管理的东西：
  DataSource    → 启动时创建，全程不变
  RedisClient   → 启动时创建，全程不变
  UserService   → 启动时创建，全程不变

Repository 不适合：
  → 你事先不知道用户要操作哪个仓库
  → 请求来了才知道是 alice/notes 还是 bob/notes
  → 每个请求都要 new 一个不同的 Repository
```

1.Spring 能帮你管的是工厂，不是 Repository 本身：

2.Handler 做成 Spring 单例会出 bug。就变成了所有请求都用同一个仓库了，要解决就得用 `@Scope("prototype")`，但那样每个请求都要手动从容器拿 bean，和直接 new 没区别。

3.CLI 启动 Spring 太慢

```tex
当前 CLI：
  java gitlet.Main add test.txt
  → JVM 启动 → 执行 → 退出
  → 耗时 ~5ms

如果加 Spring：
  java gitlet.Main add test.txt
  → JVM 启动 → SpringApplication.run() → 扫描 bean → 初始化容器 → 执行 → 退出
  → 耗时 ~1000ms（慢 200 倍）
```

4.手动接线就 5 行，没必要上容器,为 3 行代码引入一个框架，负收益。



问题：

static是同步阻塞的嘛？

static 本身只是"属于类"的意思，不加 synchronized 的 static 方法并不具备任何同步语义，多线程可以自由并发调用。

行锁（Row Lock）？

行锁是数据库层面的并发控制机制，和 Java 的 synchronized 是完全不同的东西。/

InnoDB 的行锁是加在索引上的，不是加在数据行上：
如果 WHERE 条件命中了索引，才会锁行
如果 WHERE 条件没走索引，会退化成表锁

问题：

远程仓库的架构设计？

解决：

**Object Storage（你的 `.gitlet/` 目录）**：只用来存**不可变的、大体积的** Git 对象。

- `Objects/commits/abc123` (Commit 对象)
- `Objects/sha_a1` (Blob 对象)
- **绝对不要在这里存 `refs/heads/` 和 `HEAD`！** 它们是高频变动的元数据。

**MySQL**：用来存**高频变动的、需要原子操作的、需要复杂查询的**元数据。

- 分支指针表 `t_branch_pointer` 才是云端的 `refs/heads/`。
- `HEAD` 在云端是个伪概念，云端通常只有默认分支（Default Branch）。因为云端不存在“当前工作目录”，只有在 clone 的时候，才会决定 checkout 哪个分支

> 放数据库（结构化、高频变更、需要查询）：
>   ✅ 用户信息（User）
>   ✅ 仓库元数据（Repo：名字、描述、owner、默认分支）
>   ✅ 分支指针（Branch：分支名 → SHA）
>   ✅ 权限信息（Permission）
>   ✅ 保护分支规则（ProtectedBranch）
>
> 放文件系统（大体积、内容寻址、只增不改）：
>   ✅ Blob 对象（文件内容，按 SHA-1 命名）
>   ✅ Commit 对象（提交历史，按 SHA-1 命名）
>
> 原则：
>   小的、会变的、需要查的 → 数据库
>   大的、不变的、按 SHA 找的 → 文件系统
