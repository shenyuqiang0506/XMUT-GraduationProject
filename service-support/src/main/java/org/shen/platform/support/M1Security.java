package org.shen.platform.support;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import feign.RequestInterceptor;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("m1")
public class M1Security {
    @Bean
    UserDetailsService smokeUser(Environment environment,
                                 @Value("${M1_SMOKE_USERNAME}") String username,
                                 @Value("${M1_SMOKE_PASSWORD}") String password) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))
                || environment.acceptsProfiles(Profiles.of("prod")) || password.length() < 24) {
            throw new IllegalStateException("M1 probes require dev/test and explicit credentials");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("M1_PROBE").build());
    }

    @Bean
    SecurityFilterChain smokeSecurity(HttpSecurity http, ObjectMapper json) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().hasRole("M1_PROBE"))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setHeader("WWW-Authenticate", "Basic realm=\"M1 dev\"");
                    response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getOutputStream(), ApiResponse.error(
                            "UNAUTHORIZED", "需要开发验证凭据", MDC.get(TraceIds.MDC_KEY)));
                }))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    RequestInterceptor m1FeignHeaders(@Value("${M1_SMOKE_USERNAME}") String username,
                                      @Value("${M1_SMOKE_PASSWORD}") String password) {
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return request -> {
            request.header("Authorization", auth);
            request.header(TraceIds.HEADER, TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)));
        };
    }
}
