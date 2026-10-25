package com.huahuo.filter;

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
 * 需要身份的操作在接口层返回未登录。与 remote-gitlet 的 JwtAuthFilter 保持同一套语义。
 */
@Slf4j
@Component
public class UserIdentityFilter extends OncePerRequestFilter {

    /** 注入到 HttpServletRequest 的 attribute key */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String BEARER_PREFIX = "Bearer ";

    // @Lazy：KeyPairManager 初始化要连 Redis，延迟到首次请求才实例化，避免启动期强依赖
    @Lazy
    @Autowired
    private KeyPairManager keyPairManager;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Long userId = resolveUserId(request);
            if (userId != null) {
                request.setAttribute(USER_ID_ATTRIBUTE, userId);
            }
        } catch (Exception e) {
            log.debug("解析 JWT 失败，按匿名处理: {}", e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    /** 无 token / 非法 / 过期 / 已登出均返回 null */
    private Long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        if (!keyPairManager.verifyToken(token)) {
            return null;
        }
        JWT jwt = JWTUtil.parseToken(token);
        if (isExpired(jwt) || keyPairManager.isBlacklisted(token)) {
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
            return false;
        }
        return System.currentTimeMillis() > epochSeconds * 1000;
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
