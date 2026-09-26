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

问题：

BCrypt 密码哈希算法

是什么

BCrypt 是一种基于 **Blowfish 密码算法** 的密码哈希函数，由 Niels Provos 和 David Mazières 于 1999 年提出。它是目前最常用、最推荐的密码存储方案之一。

 核心特点

| 特性           | 说明                                                         |
| -------------- | ------------------------------------------------------------ |
| **单向性**     | 只能加密，不能解密（不像 AES 等对称加密）                    |
| **内置盐值**   | 每次哈希自动生成随机 salt，相同密码每次哈希结果不同          |
| **可调节成本** | 通过 `cost factor`（轮数）控制计算复杂度，对抗硬件加速暴力破解 |
| **慢速设计**   | 故意设计得慢，增加暴力破解的成本                             |

哈希结果格式

```
$2a$12$R9h/ipB3.dummySaltXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
 ^^^^  ^^  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  |    |              |
  |    |              └── 哈希值（53字符）
  |    └── cost factor（2^12 = 4096 轮）
  └── 算法版本（2a）
```

 工作原理简述

1. 生成 128 位随机 **salt**
2. 将 salt + 密码合并
3. 使用改良的 Blowfish 密钥调度算法（称为 **Eksblowfish**）进行多轮加密
4. 轮数 = \(2^{cost}\)，cost 越大越慢
5. 输出包含版本、cost、salt、hash 的完整字符串

在 Spring Boot 中的使用

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// 创建编码器，cost 默认 10
BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

// 注册时：加密密码
String encoded = encoder.encode("myPassword123");
// 结果类似：$2a$12$R9h/ipB3.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

// 登录时：验证密码（不需要手动提取 salt，BCrypt 自动从哈希中解析）
boolean matches = encoder.matches("myPassword123", encoded); // true
```

为什么推荐 BCrypt

- **自动加盐**：不需要你自己管理 salt 字段，salt 内嵌在结果中
- **抗 GPU/ASIC 攻击**：Blowfish 的密钥调度依赖大量内存操作，难以并行化
- **可演进**：随着硬件进步，只需调大 cost 参数即可

与其他方案对比

| 方案           | 优缺点                                             |
| -------------- | -------------------------------------------------- |
| **MD5/SHA**    | 太快，容易被暴力破解，**不推荐**                   |
| **SHA + Salt** | 需要自己管理 salt，且仍不够慢                      |
| **BCrypt**     | ✅ 内置 salt、可调慢速、广泛支持                    |
| **Argon2**     | 更新的方案，抗内存攻击更强，但生态不如 BCrypt 成熟 |
| **PBKDF2**     | NIST 推荐，但 GPU 抗性不如 BCrypt/Argon2           |

Cost Factor 参考

| Cost | 轮数   | 大致耗时（现代CPU） |
| ---- | ------ | ------------------- |
| 10   | 1,024  | ~60ms               |
| 12   | 4,096  | ~250ms              |
| 14   | 16,384 | ~1s                 |

一般推荐 **cost = 10~12**，在安全性和用户体验之间取得平衡。

---

问题：

JWT哪个加密算法用的多？

HS256（HMAC-SHA256，对称算法，**练手 / 单体项目首选**）

> 同一个密钥，**既用来生成签名，又用来校验签名**

- 优点：代码简单、性能高，只需要配置一个 secret 秘钥，上手最快
- 缺点：所有校验 JWT 的服务都必须持有这个 secret，秘钥一旦泄露，攻击者可以随便伪造 JWT
- 适用：**单体 SpringBoot 项目、课程毕设、前后端分离单服务**（你写 demo 就用这个）

> 注意：秘钥不能太短，要足够长，不然容易被暴力破解

RS256（RSA-SHA256，非对称，企业微服务常用）

> 私钥 (privateKey)：**只有认证服务器保存，用来签发 JWT** 公钥 (publicKey)：可以公开，其他微服务 / 网关只用公钥校验，**公钥不能生成 token**

- 优点：安全，就算下游服务被入侵，拿到公钥也伪造不了 JWT
- 缺点：需要生成 RSA 公私钥对，代码稍微复杂一点，签名验证性能略低
- 适用：微服务、SSO 单点登录、第三方授权、多服务场景

问题：

为什么用RS256？

解决：

> 私钥 (privateKey)：**只有认证服务器保存，用来签发 JWT** 
>
> 公钥 (publicKey)：可以公开，其他微服务 / 网关只用公钥校验，**公钥不能生成 token**

- 优点：安全，就算下游服务被入侵，拿到公钥也伪造不了 JWT
- 缺点：需要生成 RSA 公私钥对，代码稍微复杂一点，签名验证性能略低
- 适用：微服务、SSO 单点登录、第三方授权、多服务场景

问题：

如果公钥泄露了怎么办？私钥是怎么检测的？

## 公钥泄露了怎么办？

**公钥泄露完全没问题，它本来就是公开的。**

```
公钥 = 验钞机的紫外线灯，人人都可以有一盏
       它只能用来"照一照"验证真伪
       不能用来"印假钞"
```

| 泄露场景     | 后果                                          |
| ------------ | --------------------------------------------- |
| **公钥泄露** | ✅ 没事，别人最多只能验证 Token 真假，伪造不了 |
| **私钥泄露** | ❌ 灾难，别人可以伪造任意 Token，冒充任何用户  |

所以私钥才是核心机密，公钥本身就是设计给所有人看的。

## 验证是怎么工作的？

RS256 的数学原理保证了**只有对应的私钥签出来的东西，才能被公钥验证通过**：

```
签发（user-service）                    验证（gateway-service）
━━━━━━━━━━━━━━━━                      ━━━━━━━━━━━━━━━━━━
Token 内容: { userId: 1 }              收到 Token
     ↓                                      ↓
用 私钥 对内容做签名                          ↓
     ↓                                 用 公钥 验证签名
Token = 内容 + 签名                      ↓
     ↓                                 签名合法？ → userId=1 可信
发给前端                                  签名非法？ → 拒绝，Token 被篡改
```

**攻击者即使拿到公钥 + Token，也推导不出私钥**，所以伪造不了新 Token。这就是 RSA 非对称加密的数学安全性。

## 真正需要担心的是私钥泄露

如果私钥泄露了，攻击者可以：

```java
// 攻击者拿到私钥后，可以伪造任意用户的 Token
String fakeToken = JWT.create()
    .setPayload("userId", 999)          // 冒充任何人
    .setPayload("username", "admin")
    .sign(JWTSignerUtil.rs256(泄露的私钥));  // 签名完全合法
```

**防护手段**：

| 手段                        | 说明                                  |
| --------------------------- | ------------------------------------- |
| **私钥只存 Redis/安全存储** | 不写日志、不传前端、不进代码仓库      |
| **设置 Token 过期时间**     | 即使泄露，过期后自动失效              |
| **密钥轮换**                | 定期重新生成密钥对，旧 Token 全部失效 |
| **Token 黑名单**            | 用户登出时把 Token 加入 Redis 黑名单  |

问题：

集成测试用H2内存数据库，内存数据库是什么？

解决：

H2 内存数据库是什么

H2 是一个**纯 Java 的轻量级数据库**，"内存数据库"的意思是数据存在内存里，**程序一关数据就没了**。专门用来跑测试，不需要你安装 MySQL，也不需要连远程数据库。

```
正常开发：代码 → MySQL（192.168.12.168）
跑测试时：代码 → H2（内存里，用完就丢）
```

好处是：
- 测试不依赖远程数据库，断网也能跑
- 每次测试都是干净的库，不会有脏数据干扰
- 速度快（内存操作）

但对你来说，**H2 不是必须的**。你也可以直接用测试环境的 MySQL 跑集成测试。如果你不想引入 H2，把 pom 里的 H2 依赖删掉，测试配置文件改成连你的 MySQL 就行：

```yaml
# src/test/resources/application-test.yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.12.168:3306/gitlethub_test
    username: root
    password: 你的密码
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 192.168.12.168
      port: 6379
      database: 1
```

**简单说**：H2 就是个测试用的临时数据库，不用也行，看你方便。

这段是「**非快进校验**」，我用大白话 + 场景讲清楚。

问题：

## 先说这段代码在干嘛

```java
JsonNullable<String> expectedOldSha = refUpdateRequest.getExpectedOldSha();
if (expectedOldSha != null && expectedOldSha.isPresent()
        && !Objects.equals(expectedOldSha.get(), branchPointer.getCommitSha())) {
    return Result.fail(ResultCode.VERSION_CONFLICT, ...);
}
```

翻译成人话：

> 客户端 push 时会带一句话：**「我以为远程分支现在还指向 X」**（这个 X 就是 `expectedOldSha`）。
> 服务端就核对：**远程分支当前实际指向的**（`branchPointer.getCommitSha()`）**是不是 X？**
> - 是 → 说明没人动过，放行。
> - 不是 → 说明在你 push 之前，**别人已经推了新东西**，你这份是过时的，拒绝（返回冲突）。

---

## 三个条件逐个拆

| 条件                              | 含义                                                         |
| --------------------------------- | ------------------------------------------------------------ |
| `expectedOldSha != null`          | 字段对象本身存在（防御性判空）                               |
| `expectedOldSha.isPresent()`      | 客户端**确实传了**这个值（没传就跳过校验，比如第一次建分支） |
| `!Objects.equals(期望值, 实际值)` | 期望的旧指向 ≠ 服务端当前指向 → 冲突                         |

三个都成立才拒绝。

---

## 为什么要这么做？举个真实场景

```
初始：远程 master 指向 commit C1

你（客户端A）：
  1. pull 下来，看到 master = C1
  2. 本地基于 C1 提交，做出 C2
  3. 准备 push，带上 expectedOldSha = "C1"（我看到的是 C1）

但在你 push 之前，同事（客户端B）抢先推了：
  远程 master 已经变成 C3 了

你 push 到达服务端：
  expectedOldSha.get()          = "C1"   ← 你以为的
  branchPointer.getCommitSha()  = "C3"   ← 实际的
  两者不相等 → 拒绝！返回 VERSION_CONFLICT
```

**如果不做这个校验**：你的 C2 会直接把 master 从 C3 覆盖回 C2，**同事的 C3 就凭空消失了**（这叫「丢失更新」）。

---

## `JsonNullable` 是什么？为什么不用普通 String？

普通 `String` 分不清这两种情况：
- 客户端**没传**这个字段
- 客户端传了但值是 **null**

`JsonNullable` 是 OpenAPI 生成的包装类，能区分：
- `isPresent() == false` → 客户端压根没传（比如**新建分支**，此时远程还没有任何指向，没得比）
- `isPresent() == true` → 客户端传了，用 `.get()` 取出真实值来比

所以「第一次推送新分支」时客户端不传 `expectedOldSha`，这段校验自动跳过，不会误拦。

---

## 和乐观锁（version）的区别

你之前不是已经加了 `version` 乐观锁吗？两者不重复，是**两道关卡**：

```
expectedOldSha（业务语义层）：
  比的是「commit 指向」，客户端主动声明，语义清晰
  → 拦截「我 pull 之后、push 之前」这段时间别人插队的情况

version（数据库层）：
  比的是「行版本号」，数据库自动维护，客户端看不到
  → 兜底拦截「两个请求几乎同时到达」的极限并发
```

一句话：**`expectedOldSha` 是给用户看的语义校验，`version` 是给数据库兜底的最终防线。** 正常情况下前者就能拦住，后者是保险。

问题：

HttpClient 需要完整 URL（包括网关地址），不像 OpenFeign 那样只写相对路径。为什么

OpenFeign 依赖 Spring Cloud 的服务发现（Nacos），它知道 remote-gitlet 服务在哪台机器、哪个端口，所以自动拼接完整 URL。
HttpClient 是裸 HTTP 客户端，没有服务发现能力，你必须告诉它完整地址。

问题：

客户端手写JSON的话，“需要反序列化（JSON → 对象），手写解析器容易出 bug。”这个传到远程不还是得解析吗？

对，但**解析是服务端的事，不是客户端的事**。

```
gitlet_core (客户端)                    remote-gitlet (服务端)
┌─────────────────┐                  ┌──────────────────────┐
│ 手写 JSON 字符串 │ ──── HTTP ────→  │ Jackson 反序列化      │
│ 不需要 Jackson   │                  │ 已有 Jackson（Spring 自带）│
│ 零依赖           │                  │ 解析成 GitletObject   │
└─────────────────┘                  └──────────────────────┘
```

**服务端**是 Spring Boot，天然有 Jackson，收到 JSON 字符串后 `@RequestBody ObjectUploadRequest` 自动反序列化——这是服务端的事，跟你客户端怎么生成 JSON 没关系。

**客户端**只需要保证发出去的是**合法 JSON 字符串**就行。服务端不在乎你是手写的、StringBuilder 拼的、还是用库序列化的——只要格式对，Jackson 都能解析。

所以：
- 客户端手写 JSON → 零依赖 ✅
- 服务端用 Jackson 反序列化 → 现成的 ✅

**两边各用各的方式，互不影响。** 这就是 HTTP + JSON 的好处——协议是通用的，实现是自由的。

问题：

pull操作获取远程仓库指向的SHA1值为null

解决：

​	猜测：初始的master分支默认指向commit为null，因为远程0提交。

​	实际：请求路径写错了

问题：

服务端当前 null 为什么我第一次push会告诉我提交 59681dde65ac4b9f2c3d0508a789217bcc5e3bfd 不存在于对象储存，文件不会上传但是第二次push告诉我非快进推送被拒绝：期望旧指向 59681dde65ac4b9f2c3d0508a789217bcc5e3bfd，服务端当前 null，文件正常上传？正常流程应该是直接上传不会报错的

解决：

这两次 push 的日志信息量很大,而且暴露了两个不同的问题。在下结论前,我要先看两段真实代码——`collectCommitsToPush`(为什么第一次收集到空对象)和 `nonFastForwardCheck`(为什么第二次判成非快进)。不猜,直接看。

全部代码到手,根因清楚了。你这两次报错其实是**两个不同的 bug 叠加**,而且第二次是第一次的"后遗症"。让我先确认一个关键值——服务端 `Result` 成功时的 code 到底是几,这决定第二个修复怎么写。

`ResultCode.OK = 0` 确认了——成功是 `{"code":0,...}`,失败是 `{"code":10002,...}`,但**两种情况 HTTP 状态码都是 200**(服务端用 `ResponseEntity.ok` 包装)。这正是第二个 bug 的关键。

我把两次 push 的完整链路还原出来了,你的直觉"分支没更新"是对的,但真相比这更精彩——**两个 bug 串在一起,第二次的错是第一次埋的雷**。

## 第一次 push:为什么"提交不存在于对象储存"

看日志第一行:`上传对象 []` —— **客户端一个对象都没传**。根因在 `collectCommitsToPush`(Main.java L386):

```java
while (commit != null
        && (remoteTackingSha == null || !commit.getParent().equals(remoteTackingSha))) {
    commitObjects.add(commit);
    ...
}
```

它拿**当前 commit 的父提交**去和 tracking 比,而不是拿**当前 commit 自己**比。你的状态是:
- HEAD = `59681dde`,它的 parent = `5dceb81d`
- tracking(远程已有)= `5dceb81d`

循环第一步就判断 `!59681dde.getParent().equals(5dceb81d)` → parent 正好等于 tracking → `!true` = false → **循环一次都没进,直接返回空列表**。

于是服务端收到空对象,`59681dde` 从没被上传。接着 `updateBranch` 让 master 指向 `59681dde`,服务端的守卫检查"这个 commit 在我对象库里吗?"→ 不在 → **SHA1_MISMATCH「提交不存在于对象储存」**。

## 第二次 push:为什么"非快进被拒绝",但文件却传上去了

关键问题:**两次 push 之间,tracking ref 被悄悄改成了 `59681dde`**(第二次日志里 `expectedOldSha: 59681dde`,和 newSha 一样)。谁改的?—— 第一次 push 改的,因为它**误判成功了**。

看 `parsePushResult`(RemoteApiClient L205):

```java
if (response.statusCode() == 200) {
    return new PushResult(true, "推送成功");   // 只看 HTTP 状态码!
}
```

服务端就算业务失败(`Result.fail(10002)`),也是用 `ResponseEntity.ok(...)` 返回,**HTTP 状态码依然是 200**。所以客户端把"SHA1_MISMATCH 失败"当成了"成功",于是:

```java
if (pushResult1.isSuccess() && pushResult2.isSuccess()) {   // 两个都误判为 true
    Utils.writeContents(tracking, currentBranchSha);        // 把 tracking 写成 59681dde
    Utils.message("Push successful.");                       // 还打印了"成功"(假的)
}
```

**tracking 被污染成 `59681dde`**,而服务端的 `commit_sha` 其实还是 null(第一次更新根本没成功)。

第二次 push 时:
1. tracking = `59681dde` = HEAD。`collectCommitsToPush(since=59681dde)`:因为**没有任何 commit 的 parent 是 `59681dde`**(它是最顶端),那个"比父提交"的条件永远为真 → **一路收集到初始提交,全量上传**(所以这次 6 个 commit + 5 个 blob 全传了,只有 `59681dde` 是新的,其余"已存在跳过")。
2. `updateBranch(newSha=59681dde, expectedOldSha=59681dde)`。服务端 `nonFastForwardCheck`:期望旧指向 `59681dde`,但服务端当前是 `null` → 不相等 → **非快进拒绝**。

一句话总结:**第一次的"假成功"污染了 tracking,导致第二次带着错误的 expectedOldSha 去撞服务端真实的 null。**

## 三个修复

### 修复 A:`collectCommitsToPush` 应该比"自己的 sha",不是"父提交"

正确语义:从 HEAD 往回收集,**遇到远程已有的那个 commit(tracking)就停,且不包含它**。

```java|CODE_EDIT_BLOCK|D:\Job_project\gitlethub\gitlet_core\src\main\java\gitlet\Main.java
    private static List<Commit> collectCommitsToPush(Repository repo, String remoteTackingSha) {
        File commits = repo.getPaths().getCommits();
        File headBranchFile = Utils.join(repo.getPaths().getBranchDir(),
                Utils.readContentsAsString(repo.getPaths().getHeadFile()));
        String currentCommitSha = Utils.readContentsAsString(headBranchFile);

        List<Commit> commitObjects = new ArrayList<>();
        String sha = currentCommitSha;
        // 从 HEAD 往回走：只要当前 commit 不是"远程已有的那个"就收集它，再移到父提交。
        // remoteTackingSha 为 null（首次推送）时会一直收集到初始提交（parent 为 null 时 sha 变 null，循环自然结束）。
        while (sha != null && !sha.equals(remoteTackingSha)) {
            Commit commit = Utils.readObject(Utils.join(commits, sha), Commit.class);
            if (commit == null) {
                break;
            }
            commitObjects.add(commit);
            sha = commit.getParent();
        }
        return commitObjects;
    }
```

改完后第一次 push:`sha=59681dde != 5dceb81d` → 收集它 → `sha=parent=5dceb81d == tracking` → 停。返回 `[59681dde]`,只传这一个新提交。✅

### 修复 B:`parsePushResult` 必须看业务 code,不能只看 HTTP 状态码

```java|CODE_EDIT_BLOCK|D:\Job_project\gitlethub\gitlet_core\src\main\java\gitlet\remote\RemoteApiClient.java
    private PushResult parsePushResult(HttpResponse<String> response) {
        // HTTP 非 200：网络/路由层就失败了
        if (response.statusCode() != 200) {
            return new PushResult(false, "推送失败: HTTP " + response.statusCode() + " - " + response.body());
        }
        // HTTP 200 只代表请求送达，业务成功与否要看 body 里的 code（ResultCode.OK = 0）
        String body = response.body();
        Integer code = extractJsonInt(body, "code");
        if (code != null && code == 0) {
            return new PushResult(true, "推送成功");
        }
        return new PushResult(false, "推送失败: code=" + code + ", message=" + extractJsonString(body, "message"));
    }

    private Integer extractJsonInt(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }
```

（`extractJsonString` 你文件里已经有了,直接复用。）这样第一次 push 的 `updateBranch` 会如实返回 `success=false` → **不再写 tracking、不再假报"Push successful"** → 雷不会被埋下。

### 修复 C:首次推送时不要发空的 `expectedOldSha`

现在的代码在 tracking 为 null 时会发 `"expectedOldSha":""`。服务端 `nonFastForwardCheck` 里 `"".equals(null)` 为 false → 会把"真·首次推送到空分支"也判成冲突。应该在没有 tracking 时**直接省略这个字段**,让服务端 `JsonNullable` 处于 undefined,校验自然放行:

```java|CODE_EDIT_BLOCK|D:\Job_project\gitlethub\gitlet_core\src\main\java\gitlet\remote\RemoteApiClient.java
    public PushResult updateBranch(String owner, String repo, String branch,
                                   String newSha, String expectedOldSha) {
        // 有 tracking 才带 expectedOldSha（做非快进校验）；首次推送省略该字段，让服务端放行
        String json = expectedOldSha != null
                ? String.format("{\"sha\":\"%s\",\"expectedOldSha\":\"%s\"}", newSha, expectedOldSha)
                : String.format("{\"sha\":\"%s\"}", newSha);
        // ... existing code ...
    }
```

## 你现在的仓库状态已经"脏"了,要先修数据

代码改完还不够,因为你当前:本地 tracking = `59681dde`(错的),服务端 `commit_sha` = null(但对象已上传)。直接再 push 还是会非快进拒绝。恢复步骤:

1. **删掉本地 tracking 文件**:`.gitlet/refs/remotes/{owner}/master`(让它回到 null)。
2. 重新 `push`:
   - 修复 A → tracking=null,全量收集,对象都已存在会跳过;
   - 修复 C → 省略 expectedOldSha → 服务端 null 分支放行 → `commit_sha` 终于被设成 `59681dde`;
   - 修复 B → 真成功才写 tracking = `59681dde`,这次是真的对上了。
3. 之后 `pull`,`getBranchSha` 就能读到 `59681dde` 而不是 null 了。

---

回到你上一个问题的主题——**这次日志就是最好的 debug 教材**。你如果只盯着"非快进被拒绝"那一行去改 `nonFastForwardCheck`,就修错地方了(那个校验本身没错,是喂给它的数据被污染了)。真正的线索藏在**第一次 push 的 `上传对象 []` 和那句 WARN** 里。所以下次遇到"第二次才爆的错",记得往前翻**上一次操作的日志**——很多"莫名其妙的冲突",都是上一步的静默失败埋的。

要不要我把这三处修复涉及的方法,连同"服务端 HTTP 200 包装业务错误码"这个坑,一起补进你那份 Markdown 文档?
