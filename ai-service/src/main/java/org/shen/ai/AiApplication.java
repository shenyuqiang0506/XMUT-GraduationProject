package org.shen.ai;

import org.shen.platform.support.ServletSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * AI 服务启动骨架，保留模型依赖、服务发现及共享身份校验能力。
 * 演示工具与演示接口已退役；启动本类不代表正式 AI 查询业务已经实现。
 */
@SpringBootApplication
@EnableFeignClients
@Import(ServletSupport.class)
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
