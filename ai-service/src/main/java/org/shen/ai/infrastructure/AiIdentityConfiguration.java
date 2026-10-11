package org.shen.ai.infrastructure;

import org.shen.platform.support.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile("business & !m1")
class AiIdentityConfiguration {
    @Bean IdentityLookup aiIdentityLookup(DiscoveryClient discovery) { return new RemoteIdentityLookup(discovery); }
}
