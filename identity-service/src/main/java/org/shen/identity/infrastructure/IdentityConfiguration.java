package org.shen.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.shen.platform.support.ExpiredRecordCleaner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 身份模块的密码编码配置。
 * BCrypt 工作因子为 12；数据库保存不可逆的密码摘要，登录时通过 matches 校验。
 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
@EnableScheduling
class IdentityConfiguration {
    @Bean
    ExpiredRecordCleaner identityExpiredRecords(JdbcTemplate sql) {
        return new ExpiredRecordCleaner(sql, true);
    }

    @Bean
    PasswordEncoder businessPasswords() {
        return new BCryptPasswordEncoder(12);
    }
}
