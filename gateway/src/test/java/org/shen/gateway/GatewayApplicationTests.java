package org.shen.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

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
