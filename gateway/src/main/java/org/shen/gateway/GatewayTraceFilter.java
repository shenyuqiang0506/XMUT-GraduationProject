package org.shen.gateway;

import org.shen.platform.contracts.TraceIds;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/**
 * 网关最早执行的追踪过滤器。
 * 统一请求与响应的 traceId，并移除外部传入的用户 ID、角色头，避免伪造身份进入下游。
 * 响应式调用中直接在 exchange 上传递上下文，不依赖 Servlet 线程本地变量。
 */
@Component
class GatewayTraceFilter implements WebFilter, Ordered {
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String trace = TraceIds.acceptOrCreate(exchange.getRequest().getHeaders().getFirst(TraceIds.HEADER));
        exchange.getResponse().getHeaders().set(TraceIds.HEADER, trace);
        var request = exchange.getRequest().mutate().headers(h -> {
            h.set(TraceIds.HEADER, trace);
            h.remove("X-User-Id");
            h.remove("X-Roles");
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
