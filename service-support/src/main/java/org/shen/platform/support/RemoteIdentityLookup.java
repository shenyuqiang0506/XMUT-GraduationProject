package org.shen.platform.support;

import org.shen.platform.contracts.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClientResponseException;

/**
 * 业务服务向身份服务核实令牌对应的实时身份。
 * 从服务发现获取实例，透传原始 Bearer 令牌与追踪编号；不采信调用方自报的角色。
 * 无实例或无效响应均拒绝本次业务调用，不能降级为默认放行。
 */
public final class RemoteIdentityLookup implements IdentityLookup {
    private final IdentityServiceClient http;

    public RemoteIdentityLookup(DiscoveryClient discovery) {
        this.http = new IdentityServiceClient(discovery);
    }

    public AuthActor resolve(Jwt jwt) {
        try {
            var response = http.get("/internal/auth/session", jwt.getTokenValue(),
                    new ParameterizedTypeReference<ApiResponse<AuthActor>>() {
                    });
            // 同时核对用户和会话标识，防止成功格式的错误响应被当作当前请求身份。
            if (response == null || !"OK".equals(response.code()) || response.data() == null
                    || !jwt.getSubject().equals(response.data().userId())
                    || !jwt.getClaimAsString("sid").equals(response.data().sessionId())) {
                throw new BusinessException(503, "SERVICE_UNAVAILABLE", "身份服务响应无效");
            }
            return response.data();
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 401)
                throw new BusinessException(401, "SESSION_REVOKED", "登录状态已失效");
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "身份校验暂不可用");
        }
    }
}
