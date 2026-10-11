package org.shen.platform.support;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.shen.platform.security.JwtKeys;
import org.shen.platform.security.JwtValidation;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("business & !m1")
public class BusinessSecurity {
    @Bean
    JwtDecoder businessJwtDecoder(@Value("${JWT_PUBLIC_KEY_PATH}") String keyPath,
            @Value("${JWT_ISSUER}") String issuer, @Value("${JWT_AUDIENCE}") String audience) {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtKeys.publicKey(keyPath)).build();
        decoder.setJwtValidator(JwtValidation.validator(issuer, audience));
        return decoder;
    }
    @Bean
    SecurityFilterChain businessSecurity(HttpSecurity http, IdentityLookup identities, ObjectMapper json) throws Exception {
        var sessionFilter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth instanceof JwtAuthenticationToken token) {
                    try {
                        var actor = identities.resolve(token.getToken());
                        var authorities = actor.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                        var authenticated = new UsernamePasswordAuthenticationToken(actor, token.getToken().getTokenValue(), authorities);
                        SecurityContextHolder.getContext().setAuthentication(authenticated);
                    } catch (BusinessException failure) {
                        error(response, json, failure.status(), failure.code(), failure.getMessage()); return;
                    } catch (RuntimeException unavailable) {
                        error(response, json, 503, "SERVICE_UNAVAILABLE", "身份校验暂不可用"); return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/auth/login", "/auth/refresh").permitAll()
                        .requestMatchers("/users/me", "/auth/logout", "/internal/auth/session").authenticated()
                        .requestMatchers("/internal/auth/users/**").hasAnyRole("SYSTEM_ADMIN", "RESOURCE_ADMIN")
                        .requestMatchers("/users/**").hasRole("SYSTEM_ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}).authenticationEntryPoint((request, response, failure) ->
                        error(response, json, 401, "UNAUTHORIZED", "请登录或刷新登录状态")))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                        error(response, json, 401, "UNAUTHORIZED", "请登录或刷新登录状态"))
                        .accessDeniedHandler((request, response, failure) -> error(response, json, 403, "FORBIDDEN", "无权执行此操作")))
                .addFilterAfter(sessionFilter, BearerTokenAuthenticationFilter.class).build();
    }
    private static void error(HttpServletResponse response, ObjectMapper json, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), ApiResponse.error(code, message, MDC.get(TraceIds.MDC_KEY)));
    }
    @Bean
    feign.RequestInterceptor businessFeignHeaders() {
        return request -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getCredentials() instanceof String token) { request.header("Authorization", "Bearer " + token); }
            request.header(TraceIds.HEADER, TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)));
        };
    }
}
