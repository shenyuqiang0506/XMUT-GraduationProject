package org.shen.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 验证没有 Content-Length 的分块请求同样受保护，并确认等于上限的合法请求可通过。 */
class RequestBodyLimitFilterTests {
    @Test void oversizedContentLengthIsRejectedBeforeDownstream() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login").contentLength(11));
        new RequestBodyLimitFilter(10, new ObjectMapper()).filter(exchange, ignored -> {
            throw new AssertionError("超限请求不应进入下游");
        }).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(413);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("PAYLOAD_TOO_LARGE");
    }

    private MockServerWebExchange chunked(int secondChunk) {
        var factory = new DefaultDataBufferFactory();
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/users")
                .body(Flux.just(factory.wrap(new byte[5]), factory.wrap(new byte[secondChunk]))));
    }

    @Test void oversizedChunkedBodyIsRejected() {
        var exchange = chunked(6);
        new RequestBodyLimitFilter(10, new ObjectMapper()).filter(exchange,
                e -> e.getRequest().getBody().doOnNext(DataBufferUtils::release).then()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(413);
    }

    @Test void exactLimitIsAllowed() {
        var exchange = chunked(5);
        new RequestBodyLimitFilter(10, new ObjectMapper()).filter(exchange,
                e -> e.getRequest().getBody().doOnNext(DataBufferUtils::release).then()).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
