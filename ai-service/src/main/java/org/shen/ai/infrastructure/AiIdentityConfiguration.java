package org.shen.ai.infrastructure;

import org.shen.platform.support.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.*;

/**
 * 为 AI 服务骨架接入与普通业务一致的实时身份校验。
 * 未来模型工具仍须经过业务权限，不因请求来自模型而获得额外访问范围。
 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
class AiIdentityConfiguration {
    @Bean
    IdentityLookup aiIdentityLookup(DiscoveryClient discovery) {
        return new RemoteIdentityLookup(discovery);
    }
}
