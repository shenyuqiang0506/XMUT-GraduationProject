package org.shen.graduationproject.infrastructure;

import org.shen.platform.support.ExpiredRecordCleaner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 预约库只清理过期幂等记录；导入文件、资源、占用及审计另有业务生命周期。 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
@EnableScheduling
class RecordCleanupConfiguration {
    @Bean
    ExpiredRecordCleaner bookingExpiredRecords(JdbcTemplate sql) {
        return new ExpiredRecordCleaner(sql, false);
    }
}
