package org.shen.graduationproject.infrastructure;

import java.util.List;

import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.shen.platform.support.IdentityServiceClient;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * 配置资源管理员或审批组成员前，向身份服务核实目标账号资格。
 * 两个业务库之间不直接跨库读取；透传当前操作者令牌，由身份接口执行权限校验。
 * 只有有效的资源管理员或系统管理员可以被分配管理职责。
 */
@Component
@Profile("business")
public class AuthorizedUsers {
    /**
     * 只接收管理员资格校验所需字段，避免未检查的 Map/List 强制转换。
     */
    public record UserSummary(String userId, String status, List<String> roles) {
    }

    private final IdentityServiceClient http;

    public AuthorizedUsers(DiscoveryClient discovery) {
        this.http = new IdentityServiceClient(discovery);
    }

    public void requireManager(String id) {
        CatalogAccess.id(id);
        try {
            var response = http.get("/internal/auth/users/" + id,
                    SecurityContextHolder.getContext().getAuthentication().getCredentials().toString(),
                    new ParameterizedTypeReference<ApiResponse<UserSummary>>() {
                    });
            if (response == null || response.data() == null || !"OK".equals(response.code()) || response.data().roles() == null)
                throw new BusinessException(503, "SERVICE_UNAVAILABLE", "身份响应无效");
            var roles = response.data().roles();
            if (!id.equals(response.data().userId()) || !"ACTIVE".equals(response.data().status())
                    || (!roles.contains("RESOURCE_ADMIN") && !roles.contains("SYSTEM_ADMIN")))
                throw new BusinessException(422, "INVALID_MANAGER", "管理员必须是有效的资源管理员或系统管理员");
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404)
                throw new BusinessException(422, "INVALID_MANAGER", "指定账号不存在");
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "管理员身份校验暂不可用");
        }
    }
}
