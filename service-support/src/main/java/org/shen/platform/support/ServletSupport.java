package org.shen.platform.support;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Import({M1Security.class, ServiceErrors.class, BusinessSecurity.class})
public class ServletSupport {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> traceFilter() {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String trace = TraceIds.acceptOrCreate(request.getHeader(TraceIds.HEADER));
                MDC.put(TraceIds.MDC_KEY, trace);
                response.setHeader(TraceIds.HEADER, trace);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove(TraceIds.MDC_KEY);
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
