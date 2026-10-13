package org.shen.gateway;

import org.shen.platform.contracts.*;
import org.shen.platform.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 网关的正式认证与路由配置。
 * 公开登录、刷新和健康检查；业务路径先校验 JWT，再通过服务发现转发。
 * 网关只校验令牌，实时会话和资源范围仍由下游业务服务负责。
 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
class GatewayBusinessConfiguration {
    @Bean
    ReactiveJwtDecoder gatewayJwtDecoder(@Value("${JWT_PUBLIC_KEY_PATH}") String path,
                                         @Value("${JWT_ISSUER}") String issuer, @Value("${JWT_AUDIENCE}") String audience) {
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(JwtKeys.publicKey(path)).build();
        decoder.setJwtValidator(JwtValidation.validator(issuer, audience));
        return decoder;
    }

    private static Mono<Void> error(ServerWebExchange exchange, ObjectMapper json, int status, String code) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] bytes = json.writeValueAsBytes(ApiResponse.error(code, status == 401 ? "请登录或刷新登录状态" : "无权执行此操作", exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER)));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Bean
    SecurityWebFilterChain gatewayBusinessSecurity(ServerHttpSecurity http, ObjectMapper json) {
        return http.csrf(csrf -> csrf.disable()).securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth.pathMatchers("/actuator/health/**", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .pathMatchers("/api/v1/**").authenticated().anyExchange().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {
                }).authenticationEntryPoint((exchange, failure) -> error(exchange, json, 401, "UNAUTHORIZED")))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((exchange, failure) -> error(exchange, json, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((exchange, failure) -> error(exchange, json, 403, "FORBIDDEN"))).build();
    }

    /**
     * 仅显式声明已实现的业务路径。移除前两段 /api/v1 后，按 Nacos 服务名寻找下游实例。
     */
    @Bean
    RouteLocator businessRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("identity", r -> r.path("/api/v1/auth/**", "/api/v1/users/**").filters(f -> f.stripPrefix(2)).uri("lb://identity-service"))
                .route("notifications", r -> r.path("/api/v1/notifications/**","/api/v1/admin/notifications/**").filters(f -> f.stripPrefix(2)).uri("lb://notification-service"))
                .route("booking-catalog", r -> r.path("/api/v1/resources/**", "/api/v1/availability/**", "/api/v1/admin/resources/**",
                                "/api/v1/admin/imports/**", "/api/v1/admin/approval-groups/**", "/api/v1/admin/policies/**", "/api/v1/admin/external-occupancies/**",
                                "/api/v1/applications/**", "/api/v1/admin/applications/**", "/api/v1/waitlists/**", "/api/v1/admin/outbox/**")
                        .filters(f -> f.stripPrefix(2)).uri("lb://booking-service")).build();
    }
}
