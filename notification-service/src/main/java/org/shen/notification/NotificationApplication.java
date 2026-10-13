package org.shen.notification;

import org.shen.platform.support.ServletSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** 通知服务仅持有通知库与消息凭据，使用共享公钥验签和实时身份查询。 */
@SpringBootApplication @Import(ServletSupport.class)
public class NotificationApplication {
    public static void main(String[] args){SpringApplication.run(NotificationApplication.class,args);}
}
