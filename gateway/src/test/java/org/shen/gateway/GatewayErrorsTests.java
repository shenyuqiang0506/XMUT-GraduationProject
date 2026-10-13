package org.shen.gateway;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 网关异常处理继续生效，包装后的请求体超限不会退化成依赖故障。 */
class GatewayErrorsTests {
    private void check(Throwable failure, int status, String code) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/resources"));
        new GatewayErrors(new ObjectMapper()).handle(exchange, failure).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains(code);
    }

    @Test void unknownProgrammingFailureIs500() { check(new NullPointerException("测试错误"), 500, "INTERNAL_ERROR"); }
    @Test void wrappedBodyLimitIs413() { check(new RuntimeException(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)), 413, "PAYLOAD_TOO_LARGE"); }
    @Test void connectionFailureIs503() { check(new IOException("测试连接故障"), 503, "SERVICE_UNAVAILABLE"); }
    @Test void unknownRouteRemains404() { check(new ResponseStatusException(HttpStatus.NOT_FOUND), 404, "NOT_FOUND"); }
}
