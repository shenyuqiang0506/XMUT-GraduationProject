package org.shen.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 响应式网关启动入口，使用 WebFlux 执行认证、路由和追踪。
 * 不导入共享 Servlet 配置或 JDBC 访问层，避免与 MVC 服务运行模型混用。
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
