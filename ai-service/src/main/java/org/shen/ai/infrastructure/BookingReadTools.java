package org.shen.ai.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

import org.shen.platform.contracts.*;
import org.shen.platform.support.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 固定服务与路径的只读适配层；POST 搜索/批量详情仍不产生任何预约写入。
 * 可信用户 token 由控制器安全上下文传入，并直接交给业务服务进行实时复核，模型不能指定身份。
 */
@Component
@Profile("business")
public class BookingReadTools {
    public record Reply(Object data, String traceId) {
    }

    private final DiscoveryClient discovery;
    private final RestClient http;
    private final IdentityServiceClient identities;
    private final ObjectMapper json;

    public BookingReadTools(DiscoveryClient discovery, ObjectMapper json) {
        this.discovery = discovery;
        this.json = json;
        identities = new IdentityServiceClient(discovery);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build());
        factory.setReadTimeout(Duration.ofMillis(1500));
        http = RestClient.builder().requestFactory(factory).build();
    }

    public Reply options(String mode, String token, String trace) {
        return request("GET", "/resources/options?dataMode=" + mode, null, token, trace);
    }

    public Reply rules(String mode, String token, String trace) {
        return request("GET", "/booking-rules?dataMode=" + mode, null, token, trace);
    }

    public Reply search(Map<String, Object> conditions, String mode, String token, String trace) {
        var body = new LinkedHashMap<String, Object>();
        for (String field : List.of("campus", "preferredBuilding", "startAt", "endAt", "participantCount", "requiredFeatures", "equipmentRequirements"))
            if (conditions.containsKey(field)) body.put(field, conditions.get(field));
        body.put("dataMode", mode);
        body.put("page", 1);
        body.put("size", 3);
        return request("POST", "/availability/search", body, token, trace);
    }

    public Reply details(List<String> ids, String token, String trace) {
        return request("POST", "/resources/details", ids, token, trace);
    }

    /**
     * 提交回答前再确认会话，避免模型等待期间撤销的账号继续收到业务结果。
     */
    public void verify(AuthActor original, String token) {
        var response = identities.get("/internal/auth/session", token, new ParameterizedTypeReference<ApiResponse<AuthActor>>() {
        });
        AuthActor current = response == null ? null : response.data();
        if (current == null || !original.userId().equals(current.userId()) || original.authVersion() != current.authVersion()
                || current.roles().stream().noneMatch(r -> r.equals("STUDENT") || r.equals("TEACHER")))
            throw new BusinessException(401, "SESSION_REVOKED", "登录状态已失效");
    }

    private Reply request(String method, String path, Object body, String token, String trace) {
        var instances = discovery.getInstances("booking-service");
        if (instances.isEmpty()) throw new BusinessException(503, "SERVICE_UNAVAILABLE", "预约服务暂不可用");
        try {
            var request = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(instances.getFirst().getUri() + path)
                    .header("Authorization", "Bearer " + token).header(TraceIds.HEADER, trace);
            if (body != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
            var response = request.retrieve().body(Map.class);
            if (response == null || !"OK".equals(response.get("code")) || response.get("data") == null)
                throw new BusinessException(503, "SERVICE_UNAVAILABLE", "预约服务响应格式不正确");
            return new Reply(response.get("data"), String.valueOf(response.getOrDefault("traceId", trace)));
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            String code = status == 401 ? "SESSION_REVOKED" : status == 403 ? "FORBIDDEN" : status == 404 ? "NOT_FOUND" : "SERVICE_UNAVAILABLE";
            if (status == 400 || status == 422) {
                try {
                    var parsed = json.readValue(failure.getResponseBodyAsString(), Map.class);
                    String supplied = String.valueOf(parsed.get("code"));
                    if (Set.of("TIME_RANGE_INVALID", "VALIDATION_ERROR", "QUERY_TOO_BROAD", "COVERAGE_UNKNOWN").contains(supplied))
                        code = supplied;
                } catch (RuntimeException ignored) {
                }
            }
            throw new BusinessException(status, code, "预约查询未能完成，请核对条件或稍后重试");
        } catch (ResourceAccessException unavailable) {
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "预约服务暂不可用");
        }
    }
}
