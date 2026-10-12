package org.shen.graduationproject.infrastructure;

import org.shen.platform.support.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("business")
class BookingIdentityConfiguration {
    @Bean
    IdentityLookup bookingIdentityLookup(DiscoveryClient discovery) {
        return new RemoteIdentityLookup(discovery);
    }
}
