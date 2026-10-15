package org.shen.graduationproject.infrastructure;

import java.util.List;

import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.support.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.shen.platform.security.ServiceCallSignatures;

/**
 * 审批前重新查询申请人当前资格，在预约事务开始前调用，不在数据库行锁内等待远程服务。
 */
@Component
@Profile("business")
public class AuthorizedApplicants {
    public record Summary(String userId, String status, List<String> roles) {
    }

    private final IdentityServiceClient http;
    private final byte[] serviceKey;
    private final String audience;

    public AuthorizedApplicants(DiscoveryClient discovery, @Value("${SERVICE_CALL_KEY_PATH}") String keyPath,
                                @Value("${JWT_AUDIENCE}") String audience) {
        http = new IdentityServiceClient(discovery);
        serviceKey = ServiceCallSignatures.readKey(keyPath);
        this.audience = audience;
    }

    /**
     * 定时提升不能保存用户令牌；使用仅授权查询资格的独立服务签名，在预约事务外请求。
     */
    public boolean eligibleForBackground(long userId) {
        String path = "/internal/booking/applicants/" + userId;
        try {
            var response = http.getWithHeaders(path, () -> ServiceCallSignatures.headers(serviceKey, audience, path),
                    new ParameterizedTypeReference<ApiResponse<Summary>>() {
                    });
            if (response == null || !"OK".equals(response.code()) || response.data() == null || response.data().roles() == null
                    || !Long.toString(userId).equals(response.data().userId()))
                throw new BusinessException(503, "SERVICE_UNAVAILABLE", "后台资格响应无效");
            var user = response.data();
            return "ACTIVE".equals(user.status())
                    && (user.roles().contains("STUDENT") || user.roles().contains("TEACHER"));
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return false;
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "后台资格查询暂不可用");
        }
    }

    public void requireEligible(long userId) {
        try {
            var response = http.get("/internal/auth/users/" + userId,
                    SecurityContextHolder.getContext().getAuthentication().getCredentials().toString(),
                    new ParameterizedTypeReference<ApiResponse<Summary>>() {
                    });
            if (response == null || !"OK".equals(response.code()) || response.data() == null || response.data().roles() == null
                    || !Long.toString(userId).equals(response.data().userId()))
                throw new BusinessException(503, "SERVICE_UNAVAILABLE", "申请人身份响应无效");
            var user = response.data();
            if (!"ACTIVE".equals(user.status()) || (!user.roles().contains("STUDENT") && !user.roles().contains("TEACHER")))
                throw new BusinessException(422, "APPLICANT_INELIGIBLE", "申请人已停用或失去学生、教师资格");
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404)
                throw new BusinessException(422, "APPLICANT_INELIGIBLE", "申请人账号不存在");
            if (failure.getStatusCode().value() == 401)
                throw new BusinessException(401, "SESSION_REVOKED", "登录状态已失效");
            if (failure.getStatusCode().value() == 403)
                throw new BusinessException(403, "FORBIDDEN", "无审批资格");
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "申请人资格校验暂不可用");
        }
    }
}
