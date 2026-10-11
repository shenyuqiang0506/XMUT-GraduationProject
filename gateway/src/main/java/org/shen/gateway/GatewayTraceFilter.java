package org.shen.gateway;

import org.shen.platform.contracts.TraceIds;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

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
