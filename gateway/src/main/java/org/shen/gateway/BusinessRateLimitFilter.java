package org.shen.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 已认证业务请求的用户级限流：总请求额度与搜索/导入额度同时检查。
 * 用户 ID 只从 Spring Security 已验签身份读取，外部 X-User-Id 不参与计数。
 * Redis Lua 保证检查、计数、设置 TTL 原子执行；受众与用户摘要隔离不同环境。
 */
@Component
@Profile("business")
@Slf4j
final class BusinessRateLimitFilter implements GlobalFilter, Ordered {
    // 先检查所有桶，再计数；被拒绝的请求不会继续增加计数或延长过期时间。
    static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            local retry=0
            for i,key in ipairs(KEYS) do
                local n=tonumber(redis.call('GET',key) or '0')
                if n>=tonumber(ARGV[i+1]) then
                    local ttl=redis.call('PTTL',key)
                    if ttl<0 then redis.call('PEXPIRE',key,ARGV[1]); ttl=tonumber(ARGV[1]) end
                    retry=math.max(retry,ttl)
                end
            end
            if retry>0 then return retry end
            for _,key in ipairs(KEYS) do
                local n=redis.call('INCR',key)
                if n==1 then redis.call('PEXPIRE',key,ARGV[1]) end
            end
            return 0
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;
    private final String audience;
    private final int general, search, imports;
    private final long windowMillis;

    BusinessRateLimitFilter(ReactiveStringRedisTemplate redis, ObjectMapper json,
                            @Value("${JWT_AUDIENCE}") String audience,
                            @Value("${BUSINESS_RATE_GENERAL:240}") int general,
                            @Value("${BUSINESS_RATE_SEARCH:30}") int search,
                            @Value("${BUSINESS_RATE_IMPORT:20}") int imports,
                            @Value("${BUSINESS_RATE_WINDOW_MS:60000}") long windowMillis) {
        if (general < 1 || search < 1 || imports < 1 || windowMillis < 1000 || windowMillis > 3600000)
            throw new IllegalArgumentException("业务限流配置无效");
        this.redis = redis;
        this.json = json;
        this.audience = audience;
        this.general = general;
        this.search = search;
        this.imports = imports;
        this.windowMillis = windowMillis;
    }

    public int getOrder() {
        return -10;
    }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 退出是撤销会话的安全操作，不能因查询额度耗尽而阻止；下游仍执行实时认证。
        if (exchange.getRequest().getURI().getPath().equals("/api/v1/auth/logout")) return chain.filter(exchange);
        return exchange.getPrincipal().ofType(JwtAuthenticationToken.class)
                .flatMap(auth -> check(exchange, auth).flatMap(retry -> {
                    if (retry < 0) return Mono.just(true); // Redis 故障响应已写入，不能再次写入 429。
                    if (retry == 0) return chain.filter(exchange).thenReturn(true);
                    exchange.getResponse().getHeaders().set("Retry-After", Long.toString((retry + 999) / 1000));
                    return GatewayErrors.write(exchange, json, 429, "BUSINESS_RATE_LIMITED", "请求过于频繁，请稍后重试").thenReturn(true);
                })).switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(true))).then();
    }

    private Mono<Long> check(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        String path = exchange.getRequest().getURI().getPath();
        String prefix = "business:rate:{" + digest(audience + ":" + auth.getToken().getSubject()) + "}:";
        var keys = new ArrayList<>(List.of(prefix + "general"));
        var args = new ArrayList<>(List.of(Long.toString(windowMillis), Integer.toString(general)));
        if (path.equals("/api/v1/availability/search")) {
            keys.add(prefix + "search");
            args.add(Integer.toString(search));
        } else if (path.equals("/api/v1/admin/imports") || path.startsWith("/api/v1/admin/imports/")
                || path.equals("/api/v1/users/imports") || path.startsWith("/api/v1/users/imports/")) {
            keys.add(prefix + "import");
            args.add(Integer.toString(imports));
        }
        // 只包裹 Redis 调用的错误，不把下游业务异常误判成限流服务故障。
        return redis.execute(CHECK, keys, args).single().timeout(java.time.Duration.ofMillis(750))
                .onErrorResume(failure -> {
                    log.warn("业务限流依赖不可用，traceId={}", exchange.getResponse().getHeaders().getFirst("X-Trace-Id"));
                    return GatewayErrors.write(exchange, json, 503, "SERVICE_UNAVAILABLE", "请求保护暂不可用，请稍后重试")
                            .thenReturn(-1L);
                });
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
