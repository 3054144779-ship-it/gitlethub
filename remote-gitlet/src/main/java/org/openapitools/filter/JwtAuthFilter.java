package org.openapitools.filter;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jwt.KeyPairManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;

/**
 * 解析 {@code Authorization: Bearer <JWT>}，把调用者 userId 写入 request attribute
 * （key = {@link #USER_ID_ATTRIBUTE}），接口层用 {@code @RequestAttribute} 读取。
 *
 * <p>只注入身份、不做拦截：无 token / 签名非法 / 已过期 / 已登出时一律按匿名放行，
 * 由 service 层按需鉴权（如合并 PR 仅仓库 OWNER）。这样公开读接口、以及 CLI 不带 token 的
 * push/clone 都不受影响；代价是「无效 token」不会返回 401，而是被当作未登录。
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 注入到 HttpServletRequest 的 attribute key，接口层以此名读取 */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    // @Lazy：KeyPairManager 的 @PostConstruct 要连 Redis 加载密钥对，延迟到首次请求才实例化，
    // 避免应用启动 / @SpringBootTest 上下文加载在 Redis 不可用时直接失败
    @Lazy
    @Autowired
    private KeyPairManager keyPairManager;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Long userId = resolveUserId(request);
            if (userId != null) {
                request.setAttribute(USER_ID_ATTRIBUTE, userId);
            }
        } catch (Exception e) {
            // 鉴权故障等同匿名，不能因此阻断公开读接口；需要身份的操作会在 service 层被拒
            log.debug("解析 JWT 失败，按匿名处理: {}", e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    /** 从 Bearer token 解析 userId；无 token / 非法 / 过期 / 已登出均返回 null */
    private Long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        // 先验签名（纯本地运算），避免为非法 token 白跑一次 Redis
        if (!keyPairManager.verifyToken(token)) {
            return null;
        }
        JWT jwt = JWTUtil.parseToken(token);
        if (isExpired(jwt)) {
            return null;
        }
        if (isBlacklisted(token)) {
            return null;
        }
        return toLong(jwt.getPayload().getClaim("userId"));
    }

    private boolean isExpired(JWT jwt) {
        Object exp = jwt.getPayload().getClaim("exp");
        if (exp instanceof Date date) {
            return System.currentTimeMillis() > date.getTime();
        }
        Long epochSeconds = toLong(exp);
        if (epochSeconds == null) {
            // 无 exp 视为不过期，安全性由签名保证
            return false;
        }
        return System.currentTimeMillis() > epochSeconds * 1000;
    }

    /** user-service 登出时会写 jwt:blacklist:<token>，被登出的 token 不能再算身份 */
    private boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
