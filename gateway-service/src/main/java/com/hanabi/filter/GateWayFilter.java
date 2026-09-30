package com.hanabi.filter;

import cn.hutool.core.util.StrUtil;
import jwt.KeyPairManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class GateWayFilter implements GlobalFilter, Ordered {

    @Autowired
    private KeyPairManager keyPairManager;

    // 白名单
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/user/registration", // 用户注册
            "/user/login" // 用户登录
    );

    // 过滤器逻辑
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info("用户信息校验中");
        String path = exchange.getRequest().getPath().value();
        // 如果是白名单路径，则直接放行
        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }
        // 获取请求头中的Authorization字段(token)
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        token = extractToken(token);
        // 校验通过：验签 OK 且 未被吊销（黑名单）
        if (StrUtil.isNotEmpty(token) && keyPairManager.verifyToken(token)
                && !keyPairManager.isBlacklisted(token)) {
            log.info("token校验通过");
            // 验签通过后，把已认证的 userId 透传给下游；
            // 注意：先无条件移除客户端可能自带的 X-User-Id，防止伪造身份
            Long userId = keyPairManager.getUserId(token);
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .headers(httpHeaders -> {
                        httpHeaders.remove(KeyPairManager.USER_ID_HEADER);
                        if (userId != null) {
                            httpHeaders.set(KeyPairManager.USER_ID_HEADER, String.valueOf(userId));
                        }
                    })
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        }

        // 校验失败返回未授权
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"code\":401,\"msg\":\"请登录\"}";
        return response.writeWith(Flux.just(response.bufferFactory().wrap(json.getBytes())));
    }

    // 从 Authorization 头中剥离 Bearer 前缀
    private String extractToken(String authorization) {
        if (StrUtil.isEmpty(authorization)) {
            return null;
        }
        String prefix = "Bearer";
        if (authorization.startsWith(prefix)) {
            return authorization.substring(prefix.length()).trim();
        }
        return authorization.trim();
    }

    // 执行顺序
    @Override
    public int getOrder() {
        return 0;
    }

    // 白名单校验
    private boolean isWhiteList(String path) {
        return WHITE_LIST.contains(path);
    }

}
