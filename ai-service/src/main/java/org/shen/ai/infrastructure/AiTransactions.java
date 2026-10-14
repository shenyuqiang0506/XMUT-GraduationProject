package org.shen.ai.infrastructure;

import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 仅包围认领、预算、结果提交等短数据库操作，不等待模型或 HTTP 请求。
 */
@Configuration(proxyBeanMethods = false)
@Profile("business")
class AiTransactions {
    @Bean
    TransactionTemplate aiTransactionTemplate(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(3);
        return template;
    }
}
