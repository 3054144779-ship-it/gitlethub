package com.hanabi.exception_handler;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 自定义网关限流异常处理
 */
@Component
public class CustomBlockRequestHandler implements BlockRequestHandler {
    @Override
    public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable t) {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        String msg;
        if (t instanceof FlowException) {
            msg = "请求过于频繁，请稍后再试";
        } else if (t instanceof DegradeException) {
            msg = "系统繁忙，请稍后再试";
        } else if (t instanceof ParamFlowException) {
            msg = "该热点参数访问过于频繁，请稍后再试";
        } else if (t instanceof AuthorityException) {
            status = HttpStatus.FORBIDDEN; // 403
            msg = "未授权访问";
        } else {
            msg = "请求被限流，请稍后再试";
        }
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":429,\"message\":\"" + msg + "\"}");
    }
}
