package org.shen.graduationproject.infrastructure;

import org.shen.platform.support.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.*;

/**
 * 为预约服务提供远程身份查询实现。
 * 共享安全链先验签，再调用该实现核验当前会话及角色，避免仅凭过期权限快照授权。
 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
class BookingIdentityConfiguration {
    @Bean
    IdentityLookup bookingIdentityLookup(DiscoveryClient discovery) {
        return new RemoteIdentityLookup(discovery);
    }
}
