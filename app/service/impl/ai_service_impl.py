"""
    AI Service 实现

"""
import asyncio

from dependency_injector.wiring import Provide, inject
from langgraph.graph.state import CompiledStateGraph

from config.Container import Container
from schema.domain import ChatDTO
from schema.response import Response
from service.ai_service import ChatService

class ChatServiceImpl(ChatService):

    def __init__(self):
        pass

    @inject
    async def chat(
            self,
            chat_dto: ChatDTO,
            graph: CompiledStateGraph = Provide[Container.flow_chart]
    ) -> Response:

        res = await graph.ainvoke(chat_dto)

        return Response.ok(res)

if __name__ == '__main__':
    import yaml
    from time import time
    container = Container()
    with open(r"D:\Job_project\gitlet_ai_helper\config.yaml", encoding="utf-8") as f:
        config_data = yaml.safe_load(f)
    container.config.from_dict(config_data["config"])
    container.wire(modules=[
        __name__,
        "core.intent_clarifier",
        "schema.nodes.node",
    ])

    async def test1():
        prompt = """
        执行操作：代码解释（开发者备注：有且只有这一个意图）
        public void blacklist(String authorization) {
        String pureToken = stripBearer(authorization);
        if (StrUtil.isEmpty(pureToken)) {
            return;
        }
        try {
            JWT jwt = JWTUtil.parseToken(pureToken);
            long expMillis = toEpochMillis(jwt.getPayload().getClaim("exp"));
            long remaining = expMillis - System.currentTimeMillis();
            if (remaining > 0) {
                stringRedisTemplate.opsForValue().set(
                        blacklistKey(pureToken), "logout", remaining, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("登出解析 token 失败", e);
        }
    }
        这段代码是什么意思，有啥问题吗？改了会影响啥？
        """

        chat = ChatServiceImpl()
        print("开始执行")
        start_time = time()
        chat_chat = await chat.chat(ChatDTO(query=prompt, owner=10001, repo=20005, context=None))
        print(chat_chat)
        print(f"耗时: {time() - start_time}秒")
        print("执行结束")

    async def test2():
        from schema.domain import DiffData, Context
        prompt = """
        执行操作：代码审查（开发者备注：有且只有这一个意图）
        public void blacklist(String authorization) {
        String pureToken = stripBearer(authorization);
        if (StrUtil.isEmpty(pureToken)) {
            return;
        }
        try {
            JWT jwt = JWTUtil.parseToken(pureToken);
            long expMillis = toEpochMillis(jwt.getPayload().getClaim("exp"));
            long remaining = expMillis - System.currentTimeMillis();
            if (remaining > 0) {
                stringRedisTemplate.opsForValue().set(
                        blacklistKey(pureToken), "logout", remaining, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("登出解析 token 失败", e);
        }
    }
        这段代码如果合并了会怎么样？会影响哪些范围，有什么历史的教训吗？符合仓库代码规范吗？
        """

        chat = ChatServiceImpl()
        print("开始执行")
        start_time = time()
        old_content = """
            @PostConstruct
            public void init() {
                ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
                String privateKey = ops.get(PRIVATE_KEY_REDIS);
                String publicKey = ops.get(PUBLIC_KEY_REDIS);
            
                if (StrUtil.isNotEmpty(privateKey) && StrUtil.isNotEmpty(publicKey)) {
                    byte[] privateKeyBytes = Base64.getDecoder().decode(privateKey);
                    byte[] publicKeyBytes = Base64.getDecoder().decode(publicKey);
                    keyPair = new KeyPair(
                            KeyUtil.generatePublicKey("RSA", publicKeyBytes),
                            KeyUtil.generatePrivateKey("RSA", privateKeyBytes)
                    );
                    log.info("从 Redis 加载 JWT 密钥对");
                } else {
                    // 创建密钥对
                    keyPair = KeyUtil.generateKeyPair(key, keySize);
                    ops.set(PRIVATE_KEY_REDIS, Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
                    ops.set(PUBLIC_KEY_REDIS, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
                    log.info("生成新 JWT 密钥对并存入 Redis");
                }
            }
        """
        new_content = """
        @PostConstruct
        public void init() {
            try {
                ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
                String privateKey = ops.get(PRIVATE_KEY_REDIS);
                String publicKey = ops.get(PUBLIC_KEY_REDIS);
        
                if (StrUtil.isNotEmpty(privateKey) && StrUtil.isNotEmpty(publicKey)) {
                    byte[] privateKeyBytes = Base64.getDecoder().decode(privateKey);
                    byte[] publicKeyBytes = Base64.getDecoder().decode(publicKey);
                    keyPair = new KeyPair(
                            KeyUtil.generatePublicKey("RSA", publicKeyBytes),
                            KeyUtil.generatePrivateKey("RSA", privateKeyBytes)
                    );
                    log.info("从 Redis 加载 JWT 密钥对");
                } else {
                    // 创建密钥对
                    keyPair = KeyUtil.generateKeyPair(key, keySize);
                    String privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
                    String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
                    ops.set(PRIVATE_KEY_REDIS, privateKeyBase64);
                    ops.set(PUBLIC_KEY_REDIS, publicKeyBase64);
                    log.info("生成新 JWT 密钥对并存入 Redis");
                }
            } catch (Exception e) {
                log.error("JWT密钥初始化异常，使用内存临时密钥兜底", e);
                keyPair = KeyUtil.generateKeyPair(key, keySize);
                log.warn("⚠️ 当前使用内存临时密钥，服务重启密钥会变更，旧JWT将失效");
            }
        }
        """
        diff = """
        --- a/JwtConfig.java
        +++ b/JwtConfig.java
        @@ -1,5 +1,6 @@
         @PostConstruct
         public void init() {
        +    try {
                 ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
                 String privateKey = ops.get(PRIVATE_KEY_REDIS);
                 String publicKey = ops.get(PUBLIC_KEY_REDIS);
        @@ -15,8 +16,17 @@
                 } else {
                     // 创建密钥对
                     keyPair = KeyUtil.generateKeyPair(key, keySize);
        +            String privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        +            String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
                     ops.set(PRIVATE_KEY_REDIS, privateKeyBase64);
                     ops.set(PUBLIC_KEY_REDIS, publicKeyBase64);
                     log.info("生成新 JWT 密钥对并存入 Redis");
                 }
        +    } catch (Exception e) {
        +        log.error("JWT密钥初始化异常，使用内存临时密钥兜底", e);
        +        keyPair = KeyUtil.generateKeyPair(key, keySize);
        +        log.warn("⚠️ 当前使用内存临时密钥，服务重启密钥会变更，旧JWT将失效");
        +    }
         }
        """

        dto = ChatDTO(
            query=prompt,
            owner=10001,
            repo=20005,
            context=Context(
                pr_id=12345,
                pr_name="text_pr",
                diff_data=[DiffData(
                    file_path = r'D:\Job_project\gitlethub\common-service\src\main\java\jwt\KeyPairManager.java',
                    old_content = old_content,
                    new_content = new_content,
                    diff = diff
                ),]
            )
        )
        chat_chat = await chat.chat(dto)
        print(chat_chat)
        print(f"耗时: {time() - start_time}秒")
        print("执行结束")

    async def test3():
        prompt = """
        我记得有个初始化密钥的文件，那段代码是哪个代码来着？能发给我看看吗？
        """
        dto = ChatDTO(
            query=prompt,
            owner=10001,
            repo=20005,
            context=None
        )
        chat = ChatServiceImpl()
        print("开始执行")
        start_time = time()
        chat_chat = await chat.chat(dto)
        print(chat_chat)
        print(f"耗时: {time() - start_time}秒")
        print("执行结束")

    async def test4():
        prompt = """
            执行操作：代码考古（开发者备注：有且只有这一个意图）
            public void blacklist(String authorization) {
            String pureToken = stripBearer(authorization);
            if (StrUtil.isEmpty(pureToken)) {
                return;
            }
            try {
                JWT jwt = JWTUtil.parseToken(pureToken);
                long expMillis = toEpochMillis(jwt.getPayload().getClaim("exp"));
                long remaining = expMillis - System.currentTimeMillis();
                if (remaining > 0) {
                    stringRedisTemplate.opsForValue().set(
                            blacklistKey(pureToken), "logout", remaining, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                log.warn("登出解析 token 失败", e);
            }
        }
            这段代码是怎么变成现在这样的？
            """

        chat = ChatServiceImpl()
        print("开始执行")
        start_time = time()
        chat_chat = await chat.chat(ChatDTO(query=prompt, owner=10001, repo=20005, context=None))
        print(chat_chat)
        print(f"耗时: {time() - start_time}秒")
        print("执行结束")

    async def test5():
        prompt = """
        你好
        """

        chat = ChatServiceImpl()
        print("开始执行")
        start_time = time()
        chat_chat = await chat.chat(ChatDTO(query=prompt, owner=10001, repo=20005, context=None))
        print(chat_chat)
        print(f"耗时: {time() - start_time}秒")
        print("执行结束")


    # 代码解释
    asyncio.run(test1())
    # 代码审查
    asyncio.run(test2())
    # 语义化搜索
    asyncio.run(test3())
    # 代码考古
    asyncio.run(test4())
    # 澄清器
    asyncio.run(test5())


