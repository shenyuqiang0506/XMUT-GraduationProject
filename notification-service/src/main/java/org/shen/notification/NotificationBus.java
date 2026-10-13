package org.shen.notification;

import org.shen.platform.contracts.EventTopics;
import org.shen.platform.support.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.*;

/** Quorum 队列启用至少一次死信转发，消费重试耗尽后保留消息，待人工检查和重放。 */
@Configuration(proxyBeanMethods=false) @Profile("business") @EnableRabbit
class NotificationBus {
    @Bean org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer notificationMandatory(){return template -> template.setMandatory(true);}
    @Bean IdentityLookup notificationIdentityLookup(DiscoveryClient discovery){return new RemoteIdentityLookup(discovery);}
    @Bean EventTopics notificationTopics(@Value("${JWT_AUDIENCE}") String scope){return EventTopics.forScope(scope);}
    @Bean Queue notificationQueue(EventTopics topics){return QueueBuilder.durable(topics.queue()).quorum().deadLetterExchange(topics.deadExchange()).deadLetterRoutingKey("parking").withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").build();}
    @Bean Declarables notificationTopology(EventTopics topics,Queue notificationQueue){
        var events=new DirectExchange(topics.exchange(),true,false);var dead=new DirectExchange(topics.deadExchange(),true,false);
        var parking=QueueBuilder.durable(topics.deadQueue()).quorum().build();
        return new Declarables(events,dead,notificationQueue,parking,BindingBuilder.bind(notificationQueue).to(events).with("changed"),BindingBuilder.bind(parking).to(dead).with("parking"));
    }
}
