package jwt;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.KeyUtil;
import cn.hutool.crypto.SignUtil;
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
import java.util.Base64;
import java.util.Date;

@Slf4j
@Component
public class KeyPairManager {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${com.hanabi.jwt.key:hanabi}")
    private String key;

    @Getter
    @Value("${com.hanabi.jwt.expires-time:86400}")
    private Long expiresTime;

    @Getter
    private KeyPair keyPair;

    private static final String PRIVATE_KEY_REDIS = "jwt:keys:private";
    private static final String PUBLIC_KEY_REDIS = "jwt:keys:public";

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
            keyPair = KeyUtil.generateKeyPair(key);
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
        return JWTUtil.verify(token, JWTSignerUtil.rs256(keyPair.getPublic()));
    }
}