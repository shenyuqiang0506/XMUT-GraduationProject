package org.shen.platform.support;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实本机 HTTP 服务验证故障转移，尤其保证认证拒绝不会落到另一个实例。 */
class IdentityServiceClientTests {
    private final List<HttpServer> servers = new ArrayList<>();
    private final ParameterizedTypeReference<Map<String, String>> type = new ParameterizedTypeReference<>() {};

    private DefaultServiceInstance server(int status, String result, AtomicInteger calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            calls.incrementAndGet();
            byte[] body = ("{\"result\":\"" + result + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); servers.add(server);
        return new DefaultServiceInstance(result, "identity-service", "127.0.0.1", server.getAddress().getPort(), false);
    }

    @AfterEach void stop() { servers.forEach(s -> s.stop(0)); }

    @Test void unavailableInstanceFallsBackToAnotherInstance() throws Exception {
        var a = new AtomicInteger(); var b = new AtomicInteger();
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("identity-service")).thenReturn(List.of(server(503, "down", a), server(200, "healthy", b)));
        assertThat(new IdentityServiceClient(discovery).get("/probe", "test-token", type)).containsEntry("result", "healthy");
        assertThat(a.get()).isEqualTo(1); assertThat(b.get()).isEqualTo(1);
    }

    @Test void authenticationRejectionNeverRetries() throws Exception {
        var a = new AtomicInteger(); var b = new AtomicInteger();
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("identity-service")).thenReturn(List.of(server(401, "revoked", a), server(200, "healthy", b)));
        assertThatThrownBy(() -> new IdentityServiceClient(discovery).get("/probe", "test-token", type))
                .isInstanceOf(RestClientResponseException.class);
        assertThat(a.get()).isEqualTo(1); assertThat(b.get()).isZero();
    }

    @Test void healthyInstancesRotateFirstChoice() throws Exception {
        var a = new AtomicInteger(); var b = new AtomicInteger();
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("identity-service")).thenReturn(List.of(server(200, "a", a), server(200, "b", b)));
        var client = new IdentityServiceClient(discovery);
        assertThat(client.get("/probe", "test-token", type)).containsEntry("result", "a");
        assertThat(client.get("/probe", "test-token", type)).containsEntry("result", "b");
    }

    @Test void noInstanceFailsClosed() {
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("identity-service")).thenReturn(List.of());
        assertThatThrownBy(() -> new IdentityServiceClient(discovery).get("/probe", "test-token", type))
                .isInstanceOf(BusinessException.class);
    }
}
