package org.shen.graduationproject.infrastructure;

import org.shen.platform.contracts.EventTopics;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** 持久交换器/队列提前建立，消费者暂时离线时消息仍有确定路由；返回消息不能视为发布成功。 */
@Configuration(proxyBeanMethods=false) @Profile("business")
class BookingMessages {
    @Bean EventTopics bookingEventTopics(@Value("${JWT_AUDIENCE}") String scope){return EventTopics.forScope(scope);}
    @Bean Declarables bookingTopology(EventTopics topics){
        var events=new DirectExchange(topics.exchange(),true,false);var dead=new DirectExchange(topics.deadExchange(),true,false);
        var queue=QueueBuilder.durable(topics.queue()).quorum().deadLetterExchange(topics.deadExchange()).deadLetterRoutingKey("parking").withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").build();
        var parking=QueueBuilder.durable(topics.deadQueue()).quorum().build();
        return new Declarables(events,dead,queue,parking,BindingBuilder.bind(queue).to(events).with("changed"),BindingBuilder.bind(parking).to(dead).with("parking"));
    }
    @Bean org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer mandatoryBusinessMessages(){return template -> template.setMandatory(true);}
}
