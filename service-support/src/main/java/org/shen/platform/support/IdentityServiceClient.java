package org.shen.platform.support;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;

/**
 * 身份服务只读请求的有界故障转移客户端。
 * 轮换首选实例，连接故障或 502/503/504 才尝试另一个不同实例，最多两次。
 * 401、403、404 等确定性拒绝立即交给调用方；不缓存授权，也不重试写操作。
 */
public final class IdentityServiceClient {
    private final DiscoveryClient discovery;
    private final RestClient http;
    private final AtomicInteger cursor = new AtomicInteger();

    public IdentityServiceClient(DiscoveryClient discovery) {
        this.discovery = discovery;
        // 两次尝试的超时预算约三秒，避免无限重试拖垮调用线程。
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(1));
        http = RestClient.builder().requestFactory(factory).build();
    }

    public <T> T get(String path, String token, ParameterizedTypeReference<T> type) {
        return getWithHeaders(path, () -> java.util.Map.of("Authorization","Bearer "+token), type);
    }

    /** 每次故障转移重新生成服务签名 nonce，响应丢失时也不会把前一次签名重放到另一实例。 */
    public <T> T getWithHeaders(String path, java.util.function.Supplier<java.util.Map<String,String>> headers,
            ParameterizedTypeReference<T> type) {
        var instances = discovery.getInstances("identity-service").stream().map(i -> i.getUri()).distinct().toList();
        if (instances.isEmpty()) throw new BusinessException(503, "SERVICE_UNAVAILABLE", "身份服务暂不可用");
        int start = Math.floorMod(cursor.getAndIncrement(), instances.size());
        for (int attempt = 0; attempt < Math.min(2, instances.size()); attempt++) {
            try {
                return http.get().uri(instances.get((start + attempt) % instances.size()) + path)
                        .headers(h -> headers.get().forEach(h::set))
                        .header(TraceIds.HEADER, TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)))
                        .retrieve().body(type);
            } catch (RestClientResponseException failure) {
                int status = failure.getStatusCode().value();
                if (status != 502 && status != 503 && status != 504) throw failure;
            } catch (ResourceAccessException unavailable) {
                // 超时、连接断开可切换实例；解析错误不应通过重试被隐藏。
            }
        }
        throw new BusinessException(503, "SERVICE_UNAVAILABLE", "身份服务暂不可用");
    }
}
