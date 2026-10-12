package org.shen.gateway;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("business")
@Order(-2)
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class GatewayErrors implements ErrorWebExceptionHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GatewayErrors.class);
    private final ObjectMapper json;

    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        LOG.warn("Gateway request failed traceId={} errorType={}", exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER), failure.getClass().getName());
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }
        int status = failure instanceof ResponseStatusException exception
                ? exception.getStatusCode().value() : 503;
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String code = status == 404 ? "NOT_FOUND" : "SERVICE_UNAVAILABLE";
        byte[] body = json.writeValueAsBytes(ApiResponse.error(code, "服务暂不可用",
                exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER)));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
