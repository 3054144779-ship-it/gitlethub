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
