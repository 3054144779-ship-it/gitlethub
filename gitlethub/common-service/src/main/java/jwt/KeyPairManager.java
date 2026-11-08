package jwt;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.KeyUtil;
import cn.hutool.crypto.SignUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSignerUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class KeyPairManager {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${com.hanabi.jwt.key:RSA}")
    private String key;

    @Value("${com.hanabi.jwt.key-size:2048}")
    private int keySize;

    @Getter
    @Value("${com.hanabi.jwt.expires-time:86400}")
    private Long expiresTime;

    @Getter
    private KeyPair keyPair;

    private static final String PRIVATE_KEY_REDIS = "jwt:keys:private";
    private static final String PUBLIC_KEY_REDIS = "jwt:keys:public";
    /** 黑名单 key 前缀 */
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    /** 透传给下游的用户头：客户端伪造的会被网关覆盖掉 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * 初始化密钥对
     */
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
            ops.set(PRIVATE_KEY_REDIS,Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            ops.set(PUBLIC_KEY_REDIS, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            log.info("生成新 JWT 密钥对并存入 Redis");
        }
    }

    /**
     * 创建 JWT Token
     * @param userId 用户ID
     * @param username 用户名
     * @return JWT Token
     */
    public String createToken(Long userId, String username) {
        // 使用 RSA256 算法根据私钥签发
        Date expiresTime = new Date(System.currentTimeMillis() + this.expiresTime * 1000);
        return JWT.create()
                .setPayload("userId", userId)
                .setPayload("username", username)
                .setExpiresAt(expiresTime) // key 过期时间
                .sign(JWTSignerUtil.rs256(keyPair.getPrivate()));
    }

    /**
     * 验证 JWT Token
     * @param token JWT Token
     * @return 是否验证通过
     */
    public boolean verifyToken(String token) {
        try {
            return JWTUtil.verify(token, JWTSignerUtil.rs256(keyPair.getPublic()));
        } catch (Exception e) {
            log.info("JWT 验证失败", e);
            return false;
        }
    }

    /** 统一把 "Bearer xxx" 或纯 token 剥成纯 token（剥法单一来源） */
    public String stripBearer(String authorization) {
        if (StrUtil.isEmpty(authorization)) {
            return null;
        }
        String prefix = "Bearer";
        if (authorization.startsWith(prefix)) {
            return authorization.substring(prefix.length()).trim();
        }
        return authorization.trim();
    }

    /** 登出：解析剩余有效期，写入黑名单。入参可带 "Bearer " 前缀，内部会剥。 */
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

    /** 是否已被吊销（供网关验签后二次检查） */
    public boolean isBlacklisted(String authorization) {
        String pureToken = stripBearer(authorization);
        return StrUtil.isNotEmpty(pureToken)
                && Boolean.TRUE.equals(stringRedisTemplate.hasKey(blacklistKey(pureToken)));
    }

    /** 从 token 取 userId（网关验签通过后透传给下游用） */
    public Long getUserId(String authorization) {
        String pureToken = stripBearer(authorization);
        if (StrUtil.isEmpty(pureToken)) {
            return null;
        }
        try {
            Object uid = JWTUtil.parseToken(pureToken).getPayload().getClaim("userId");
            return uid instanceof Number ? ((Number) uid).longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** exp 兼容 Date 与 NumericDate(秒) 两种形态，解析不出返回 0（→ 不写黑名单，安全） */
    private long toEpochMillis(Object expClaim) {
        if (expClaim instanceof Date) {
            return ((Date) expClaim).getTime();
        }
        if (expClaim instanceof Number) {
            return ((Number) expClaim).longValue() * 1000L;   // JWT exp 单位是秒
        }
        return 0L;
    }

    /** 黑名单 key：用 token 的 SHA-256 摘要，定长(64)且不可逆推，避免明文可重放凭证落进 Redis */
    private String blacklistKey(String pureToken) {
        return BLACKLIST_PREFIX + DigestUtil.sha256Hex(pureToken);
    }
}