package org.shen.ai;

import org.shen.platform.support.ServletSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * 智能查询服务：独立会话库、受控条件提取、只读业务工具及实时身份校验。
 * AI 只产生查询方案；用户确认后仍通过预约服务重新校验并提交审批。
 */
@SpringBootApplication
@EnableFeignClients
@Import(ServletSupport.class)
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
