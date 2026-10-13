package org.shen.gateway;

import org.shen.platform.contracts.*;
import org.springframework.http.*;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** 网关异常和保护措施沿用统一错误契约；已提交的响应不重复写入。 */
@Component
@Profile("business")
@Order(-2)
@lombok.extern.slf4j.Slf4j
final class GatewayErrors implements ErrorWebExceptionHandler {
    private final ObjectMapper json;

    GatewayErrors(ObjectMapper json) { this.json = json; }

    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getResponse().isCommitted()) return Mono.error(failure);
        int status = 500;
        // 响应式转发可能包装请求体异常；沿有限因果链识别 413，不能改写成 503。
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ResponseStatusException response) { status = response.getStatusCode().value(); break; }
            if (cause instanceof java.io.IOException || cause instanceof java.util.concurrent.TimeoutException) status = 503;
        }
        String trace = exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER);
        if (status == 500) log.error("网关内部错误，traceId={}", trace, failure);
        else log.warn("网关请求失败，traceId={}，status={}，errorType={}", trace, status, failure.getClass().getName());
        String code = switch (status) {
            case 400 -> "VALIDATION_ERROR";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 429 -> "BUSINESS_RATE_LIMITED";
            case 502, 503, 504 -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
        String message = status == 413 ? "请求内容超过大小限制" : status == 404 ? "请求路径不存在"
                : status >= 500 && status != 500 ? "服务暂不可用" : "请求未能完成";
        return write(exchange, json, status, code, message);
    }

    static Mono<Void> write(ServerWebExchange exchange, ObjectMapper json, int status, String code, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body = json.writeValueAsBytes(ApiResponse.error(code, message,
                exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER)));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
