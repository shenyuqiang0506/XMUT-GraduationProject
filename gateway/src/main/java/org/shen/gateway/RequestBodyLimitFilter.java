package org.shen.gateway;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.*;
import tools.jackson.databind.ObjectMapper;

/**
 * API 请求体统一上限，默认 3MiB，与业务服务 multipart 请求上限一致。
 * Content-Length 可提前拒绝；分块传输逐块计数，不将完整请求缓存在内存。
 * 超限时释放当前块，取消后续读取，保持统一 413 错误结构。
 */
@Component
@Profile("business")
final class RequestBodyLimitFilter implements WebFilter, Ordered {
    private final long maximum;
    private final ObjectMapper json;

    RequestBodyLimitFilter(@Value("${GATEWAY_MAX_REQUEST_BYTES:3145728}") long maximum, ObjectMapper json) {
        if (maximum < 1) throw new IllegalArgumentException("请求体大小上限必须为正数");
        this.maximum = maximum; this.json = json;
    }

    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1; }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getURI().getPath().startsWith("/api/v1/")) return chain.filter(exchange);
        if (exchange.getRequest().getHeaders().getContentLength() > maximum) return reject(exchange);
        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> {
                    var size = new AtomicLong();
                    return super.getBody().<DataBuffer>handle((buffer, sink) -> {
                        if (size.addAndGet(buffer.readableByteCount()) > maximum) {
                            DataBufferUtils.release(buffer);
                            sink.error(new TooLarge());
                        } else sink.next(buffer);
                    }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                });
            }
        };
        return chain.filter(exchange.mutate().request(request).build()).onErrorResume(TooLarge.class, failure -> reject(exchange));
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        return GatewayErrors.write(exchange, json, 413, "PAYLOAD_TOO_LARGE", "请求内容超过大小限制");
    }

    private static final class TooLarge extends ResponseStatusException {
        TooLarge() { super(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE); }
    }
}
