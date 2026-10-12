package org.shen.identity;

import org.shen.platform.support.ServletSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 身份服务启动入口。
 * 共享 MVC 安全链通过本模块 AuthenticationService 查询本地权威身份，不再远程调用自身。
 */
@SpringBootApplication
@Import(ServletSupport.class)
public class IdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
