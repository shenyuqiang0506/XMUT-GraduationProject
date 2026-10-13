package org.shen.notification;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shen.platform.contracts.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

/**
 * 收到消息不立刻 ACK；去重和通知事务提交后才确认。数据库失败最多尝试三次，之后进入死信。
 * ACK 丢失时 RabbitMQ 可重投；已有 consumed_event 使再次消费不会生成第二份通知。
 */
@Component @Profile("business") @RequiredArgsConstructor @Slf4j
class NotificationConsumer {
    private final NotificationStore store;
    private final ObjectMapper json;
    @Value("${JWT_AUDIENCE}") private String scope;
    @RabbitListener(queues="#{@notificationQueue.name}")
    public void receive(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();BookingEvent event;
        try {
            if(message.getBody().length>65536)throw new IllegalArgumentException("消息过大");
            event=json.readValue(message.getBody(),BookingEvent.class);event.validate(scope);
            if(!event.eventId().equals(message.getMessageProperties().getMessageId()))throw new IllegalArgumentException("消息编号不一致");
        } catch(RuntimeException invalid){log.warn("通知事件格式无效，进入死信");channel.basicReject(tag,false);return;}
        String hash=java.util.HexFormat.of().formatHex(sha(message.getBody()));
        for(int attempt=1;attempt<=3;attempt++){
            try {store.accept(event,hash);channel.basicAck(tag,false);return;}
            catch(RuntimeException failure){
                if(failure instanceof IllegalArgumentException||attempt==3){log.warn("通知消费未完成，eventId={}，进入死信",event.eventId());channel.basicReject(tag,false);return;}
                // 有界退避避免故障时紧密重投；实例停止则保持消息未 ACK，交由 broker 重投。
                try {Thread.sleep(100L*(1L<<(attempt-1)));}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IOException("通知消费线程停止",stop);}
            }
        }
    }
    private static byte[] sha(byte[] body){try{return java.security.MessageDigest.getInstance("SHA-256").digest(body);}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
}
