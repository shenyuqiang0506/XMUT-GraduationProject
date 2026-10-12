package org.shen.graduationproject;

import org.springframework.boot.SpringApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.shen.platform.support.ServletSupport;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 预约服务启动入口，沿用原工程目录与启动类名称。
 * 扫描本模块组件，并导入共享 MVC 认证、追踪及异常配置；运行环境由外部 profile 和私密配置决定。
 */
@SpringBootApplication
@EnableFeignClients
@Import(ServletSupport.class)
public class GraduationProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraduationProjectApplication.class, args);
    }

}
