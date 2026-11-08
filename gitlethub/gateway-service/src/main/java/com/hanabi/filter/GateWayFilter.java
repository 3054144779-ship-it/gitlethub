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
import reactor.core.scheduler.Schedulers;

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
        String token = extractToken(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (StrUtil.isEmpty(token)) {
            return unauthorized(exchange);
        }
        // 验签是纯 CPU，黑名单是阻塞 Redis 调用：后者不能跑在 Netty 事件循环上，
        // 否则并发请求会互相卡住，前端表现为大面积 20s 超时
        return Mono.fromCallable(() -> keyPairManager.verifyToken(token)
                        && !keyPairManager.isBlacklisted(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(valid -> valid
                        ? chain.filter(withUserId(exchange, token))
                        : unauthorized(exchange));
    }

    /** 验签通过后把 userId 透传给下游；先无条件移除客户端自带的 X-User-Id，防止伪造身份 */
    private ServerWebExchange withUserId(ServerWebExchange exchange, String token) {
        log.info("token校验通过");
        Long userId = keyPairManager.getUserId(token);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(httpHeaders -> {
                    httpHeaders.remove(KeyPairManager.USER_ID_HEADER);
                    if (userId != null) {
                        httpHeaders.set(KeyPairManager.USER_ID_HEADER, String.valueOf(userId));
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
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
