package org.shen.graduationproject.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.slf4j.MDC;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("business & !m1")
public class AuthorizedUsers {
    private final DiscoveryClient discovery;
    private final RestClient http;
    public AuthorizedUsers(DiscoveryClient discovery) {
        this.discovery=discovery;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));this.http=RestClient.builder().requestFactory(factory).build();
    }
    public void requireManager(String id) {
        CatalogAccess.id(id);
        var instances=discovery.getInstances("identity-service");
        if(instances.isEmpty()) throw new BusinessException(503,"SERVICE_UNAVAILABLE","身份服务暂不可用");
        try {
            var response=http.get().uri(instances.getFirst().getUri()+"/internal/auth/users/"+id)
                    .header("Authorization","Bearer "+SecurityContextHolder.getContext().getAuthentication().getCredentials())
                    .header(TraceIds.HEADER,TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)))
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<Map<String,Object>>>() {});
            if(response==null || response.data()==null || !"OK".equals(response.code())) throw new BusinessException(503,"SERVICE_UNAVAILABLE","身份响应无效");
            var roles=(java.util.List<?>)response.data().get("roles");
            if(!id.equals(response.data().get("userId")) || !"ACTIVE".equals(response.data().get("status"))
                    || (!roles.contains("RESOURCE_ADMIN") && !roles.contains("SYSTEM_ADMIN"))) throw new BusinessException(422,"INVALID_MANAGER","管理员必须是有效的资源管理员或系统管理员");
        } catch(RestClientResponseException failure) {
            if(failure.getStatusCode().value()==404) throw new BusinessException(422,"INVALID_MANAGER","指定账号不存在");
            throw new BusinessException(503,"SERVICE_UNAVAILABLE","管理员身份校验暂不可用");
        }
    }
}
