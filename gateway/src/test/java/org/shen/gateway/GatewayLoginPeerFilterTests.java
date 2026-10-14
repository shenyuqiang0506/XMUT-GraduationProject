package org.shen.gateway;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.shen.platform.security.LoginPeerSignatures;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

/** 模拟不同 TCP 对端穿过同一网关，确认转发头无法改变用于限流的真实来源。 */
class GatewayLoginPeerFilterTests {
    @TempDir Path directory;
    private GatewayLoginPeerFilter filter() throws Exception {
        Path path=directory.resolve("peer.key");Files.write(path,new byte[32]);
        return new GatewayLoginPeerFilter(path.toString(),"test-dev");
    }
    @Test void forwardedAddressesCannotOverrideSignedTcpPeer() throws Exception {
        var filter=filter();
        for(String peer:java.util.List.of("192.0.2.1","192.0.2.2")) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                    .remoteAddress(new InetSocketAddress(peer,1234)).header("X-Forwarded-For","203.0.113.99")
                    .header(LoginPeerSignatures.PEER,"203.0.113.99").header(LoginPeerSignatures.SIGNATURE,"伪造"));
            filter.filter(exchange,forwarded -> {
                var h=forwarded.getRequest().getHeaders();
                assertThat(h.getFirst(LoginPeerSignatures.PEER)).isEqualTo(peer);
                assertThat(LoginPeerSignatures.valid(new byte[32],"test-dev",peer,h.getFirst(LoginPeerSignatures.TIME),
                        h.getFirst(LoginPeerSignatures.NONCE),h.getFirst(LoginPeerSignatures.SIGNATURE),Instant.now())).isTrue();
                return Mono.empty();
            }).block();
        }
    }
    @Test void otherRoutesCannotCarryExternalLoginSignatures() throws Exception {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/resources").header(LoginPeerSignatures.PEER,"伪造"));
        filter().filter(exchange,forwarded -> {
            LoginPeerSignatures.HEADERS.forEach(header -> assertThat(forwarded.getRequest().getHeaders().getFirst(header)).isNull());
            return Mono.empty();
        }).block();
    }
}
