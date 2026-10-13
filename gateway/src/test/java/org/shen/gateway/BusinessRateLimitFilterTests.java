package org.shen.gateway;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证限流错误契约、公开请求、Redis 故障关闭策略及下游异常归属。 */
class BusinessRateLimitFilterTests {
    private MockServerWebExchange exchange(boolean authenticated) {
        var builder = MockServerWebExchange.builder(MockServerHttpRequest.post("/api/v1/availability/search")
                .header("X-User-Id", "伪造用户"));
        if (authenticated) {
            var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject("123")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            builder.principal(new JwtAuthenticationToken(jwt));
        }
        return builder.build();
    }

    private BusinessRateLimitFilter filter(ReactiveStringRedisTemplate redis) {
        return new BusinessRateLimitFilter(redis, new ObjectMapper(), "test-dev", 100, 2, 2, 60000);
    }

    @Test void exceededQuotaReturns429AndRetryAfter() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(eq(BusinessRateLimitFilter.CHECK), anyList(), anyList())).thenReturn(Flux.just(1501L));
        var exchange = exchange(true);
        filter(redis).filter(exchange, ignored -> { throw new AssertionError("超额请求不能进入下游"); }).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("BUSINESS_RATE_LIMITED");
    }

    @Test void redisFailureReturns503OnlyOnce() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(eq(BusinessRateLimitFilter.CHECK), anyList(), anyList())).thenReturn(Flux.error(new IllegalStateException("测试 Redis 故障")));
        var exchange = exchange(true);
        filter(redis).filter(exchange, ignored -> { throw new AssertionError("保护不可用时不能放行"); }).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("SERVICE_UNAVAILABLE").doesNotContain("BUSINESS_RATE_LIMITED");
    }

    @Test void downstreamFailureIsNotRewrittenAsRedisFailure() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(eq(BusinessRateLimitFilter.CHECK), anyList(), anyList())).thenReturn(Flux.just(0L));
        var failure = new IllegalStateException("测试下游异常");
        assertThatThrownBy(() -> filter(redis).filter(exchange(true), ignored -> Mono.error(failure)).block())
                .isSameAs(failure);
    }

    @Test void anonymousRequestsUseExistingLoginProtection() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        filter(redis).filter(exchange(false), ignored -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertThat(calls.get()).isEqualTo(1); verifyNoInteractions(redis);
    }

    @Test void approvedRequestIsForwardedExactlyOnce() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(eq(BusinessRateLimitFilter.CHECK), anyList(), anyList())).thenReturn(Flux.just(0L));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        filter(redis).filter(exchange(true), ignored -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void exhaustedQuotaCannotBlockLogout() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var exchange = exchange(true).mutate().request(request -> request.path("/api/v1/auth/logout")).build();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        filter(redis).filter(exchange, ignored -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertThat(calls.get()).isEqualTo(1); verifyNoInteractions(redis);
    }
}
