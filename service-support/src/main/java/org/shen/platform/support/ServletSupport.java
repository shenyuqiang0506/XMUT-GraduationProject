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

/**
 * MVC 服务共享配置：注册追踪过滤器、业务认证和异常处理。
 * 网关采用 WebFlux，不导入本配置，避免将 Servlet 组件混入响应式运行环境。
 */
@Configuration(proxyBeanMethods = false)
@Import({ServiceErrors.class, BusinessSecurity.class})
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
                    // 容器会复用工作线程；必须在 finally 清理追踪编号，防止下一次请求误用上一次日志上下文。
                    MDC.remove(TraceIds.MDC_KEY);
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
