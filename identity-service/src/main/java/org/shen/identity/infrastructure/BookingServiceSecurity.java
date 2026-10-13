package org.shen.identity.infrastructure;

import java.io.IOException;
import java.time.*;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.shen.platform.contracts.*;
import org.shen.platform.security.ServiceCallSignatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 专用链仅覆盖内部只读资格接口；不能创建用户、修改角色或访问其他人的会话。
 * 独立签名与一次性 nonce 通过后才赋予 BOOKING_SERVICE，普通用户 JWT 不能走这条链。
 */
@Configuration(proxyBeanMethods=false)
@Profile("business")
class BookingServiceSecurity {
    @Bean @Order(0)
    SecurityFilterChain bookingServiceCalls(HttpSecurity http,StringRedisTemplate redis,ObjectMapper json,
            @Value("${SERVICE_CALL_KEY_PATH}") String keyPath,@Value("${JWT_AUDIENCE}") String audience) throws Exception {
        byte[] key=ServiceCallSignatures.readKey(keyPath);
        var filter=new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                    throws ServletException,IOException {
                String nonce=request.getHeader(ServiceCallSignatures.NONCE);
                if(!"GET".equals(request.getMethod())||request.getQueryString()!=null
                        ||!request.getRequestURI().matches("/internal/booking/applicants/[1-9][0-9]{0,18}")
                        ||!ServiceCallSignatures.valid(key,audience,request.getRequestURI(),request.getHeader(ServiceCallSignatures.TIME),
                            nonce,request.getHeader(ServiceCallSignatures.SIGNATURE),Instant.now())) {
                    error(response,json,401,"UNAUTHORIZED","服务调用签名无效");return;
                }
                try {
                    if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("campus:service-nonce:"+EventTopics.digest(audience)+":"+nonce,"1",Duration.ofSeconds(30)))) {
                        error(response,json,401,"UNAUTHORIZED","服务调用签名已使用");return;
                    }
                } catch(org.springframework.dao.DataAccessException unavailable) {
                    error(response,json,503,"SERVICE_UNAVAILABLE","服务调用验真暂不可用");return;
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("booking-service",null,
                        List.of(new SimpleGrantedAuthority("BOOKING_SERVICE"))));
                chain.doFilter(request,response);
            }
        };
        return http.securityMatcher("/internal/booking/applicants/**").csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("BOOKING_SERVICE"))
                .addFilterBefore(filter,AnonymousAuthenticationFilter.class).build();
    }
    private static void error(HttpServletResponse response,ObjectMapper json,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getOutputStream(),ApiResponse.error(code,message,org.slf4j.MDC.get(TraceIds.MDC_KEY)));
    }
}
