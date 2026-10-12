package org.shen.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关最小上下文验证，确认运行模型为响应式且没有意外引入 JDBC 数据源或 MVC DispatcherServlet。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTests {
    @Autowired ApplicationContext context;
    @Autowired RouteLocator routes;

    @Test
    void reactiveGatewayStartsWithoutJdbcOrMvc() {
        assertThat(context.getClass().getName()).contains("Reactive");
        assertThat(routes).isNotNull();
        assertThat(context.containsBean("dataSource")).isFalse();
        assertThat(context.containsBean("dispatcherServlet")).isFalse();
    }
}
