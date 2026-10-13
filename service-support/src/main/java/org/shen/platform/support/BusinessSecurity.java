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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

/**
 * MVC 业务服务的 JWT 认证与实时授权入口。
 * 先验签与校验声明，再通过 IdentityLookup 读取实时会话和角色，并替换安全上下文。
 * 因此业务层收到的 AuthActor 来自受信任身份服务，而非客户端请求头。
 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
@Slf4j
public class BusinessSecurity {
    @Bean
    JwtDecoder businessJwtDecoder(@Value("${JWT_PUBLIC_KEY_PATH}") String keyPath,
                                  @Value("${JWT_ISSUER}") String issuer, @Value("${JWT_AUDIENCE}") String audience) {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtKeys.publicKey(keyPath)).build();
        decoder.setJwtValidator(JwtValidation.validator(issuer, audience));
        return decoder;
    }

    /**
     * 注册无状态安全链：不创建 HTTP 登录会话，所有受保护请求都携带访问令牌。
     */
    @Bean
    SecurityFilterChain businessSecurity(HttpSecurity http, IdentityLookup identities, ObjectMapper json) throws Exception {
        var sessionFilter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth instanceof JwtAuthenticationToken token) {
                    try {
                        // 把已验签 JWT 解析为实时操作者，并按当前角色构造 Spring Security 授权信息。
                        var actor = identities.resolve(token.getToken());
                        var authorities = actor.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                        var authenticated = new UsernamePasswordAuthenticationToken(actor, token.getToken().getTokenValue(), authorities);
                        SecurityContextHolder.getContext().setAuthentication(authenticated);
                    } catch (BusinessException failure) {
                        error(response, json, failure.status(), failure.code(), failure.getMessage());
                        return;
                    } catch (RestClientException | DataAccessResourceFailureException | TransientDataAccessException unavailable) {
                        log.warn("身份校验依赖不可用，traceId={}", MDC.get(TraceIds.MDC_KEY), unavailable);
                        error(response, json, 503, "SERVICE_UNAVAILABLE", "身份校验暂不可用");
                        return;
                    } catch (RuntimeException failure) {
                        log.error("身份校验内部错误，traceId={}", MDC.get(TraceIds.MDC_KEY), failure);
                        error(response, json, 500, "INTERNAL_ERROR", "服务内部错误，请联系管理员");
                        return;
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
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {
                }).authenticationEntryPoint((request, response, failure) ->
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

    /**
     * 内部 Feign 调用沿用当前用户的 Bearer 凭据和 traceId，不创建绕过身份校验的服务专用身份。
     */
    @Bean
    feign.RequestInterceptor businessFeignHeaders() {
        return request -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getCredentials() instanceof String token) {
                request.header("Authorization", "Bearer " + token);
            }
            request.header(TraceIds.HEADER, TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)));
        };
    }
}
