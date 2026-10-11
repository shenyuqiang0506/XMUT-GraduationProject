package org.shen.gateway;

import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("m1")
class M1GatewayConfiguration {
    @Bean
    MapReactiveUserDetailsService smokeUser(Environment environment,
                                            @Value("${M1_SMOKE_USERNAME}") String username,
                                            @Value("${M1_SMOKE_PASSWORD}") String password) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))
                || environment.acceptsProfiles(Profiles.of("prod")) || password.length() < 24) {
            throw new IllegalStateException("M1 probes require dev/test and explicit credentials");
        }
        return new MapReactiveUserDetailsService(User.withUsername(username)
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("M1_PROBE").build());
    }

    @Bean
    SecurityWebFilterChain smokeSecurity(ServerHttpSecurity http, ObjectMapper json) {
        return http.csrf(csrf -> csrf.disable())
                .securityContextRepository(org.springframework.security.web.server.context.NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth.pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/api/v1/m1/**").hasRole("M1_PROBE").anyExchange().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((exchange, exception) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    exchange.getResponse().getHeaders().set("WWW-Authenticate", "Basic realm=\"M1 dev\"");
                    byte[] body = json.writeValueAsBytes(ApiResponse.error("UNAUTHORIZED", "需要开发验证凭据",
                            exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER)));
                    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
                })).build();
    }

    @Bean
    RouteLocator m1Routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("m1-booking", route -> route.path("/api/v1/m1/booking/**")
                        .filters(filter -> filter.rewritePath("/api/v1/m1/booking/(?<rest>.*)", "/internal/m1/${rest}"))
                        .uri("lb://booking-service"))
                .route("m1-ai", route -> route.path("/api/v1/m1/ai/**")
                        .filters(filter -> filter.rewritePath("/api/v1/m1/ai/(?<rest>.*)", "/internal/m1/${rest}"))
                        .uri("lb://ai-service"))
                .build();
    }
}
