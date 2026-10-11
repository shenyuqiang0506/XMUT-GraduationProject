package org.shen.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("business & !m1")
class IdentityConfiguration {
    @Bean
    PasswordEncoder businessPasswords() {
        return new BCryptPasswordEncoder(12);
    }
}
