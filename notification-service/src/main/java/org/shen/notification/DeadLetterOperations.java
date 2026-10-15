package org.shen.notification;

import java.util.*;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;

/**
 * 当前部署死信的有限预览与人工重放：先等待新发布确认，再 ACK 原死信，失败则重新入死信队列。
 * 租约及原请求键使 HTTP 重试可回放，仍接受跨 MySQL/RabbitMQ 的至少一次重投，由通知 eventId 去重。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class DeadLetterOperations {
    private final RabbitTemplate rabbit;
    private final EventTopics topics;
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    @Value("${JWT_AUDIENCE}")
    private String scope;

    public record Replay(String reason) {
    }

    public record Result(Map<String, Object> data, boolean replayed) {
    }

    private void admin(AuthActor actor) {
        if (!actor.roles().contains("SYSTEM_ADMIN"))
            throw new BusinessException(403, "FORBIDDEN", "仅系统管理员可检查或重放死信");
    }

    private Map<String, Object> metadata(GetResponse response) {
        var item = new LinkedHashMap<String, Object>();
        item.put("eventId", response.getProps().getMessageId());
        item.put("valid", false);
        item.put("error", "消息未通过契约检查，请核对后处理");
        try {
            if (response.getBody().length > 65536) throw new IllegalArgumentException("消息过大");
            var event = json.readValue(response.getBody(), BookingEvent.class);
            event.validate(scope);
            if (!event.eventId().equals(response.getProps().getMessageId()))
                throw new IllegalArgumentException("消息编号不一致");
            item.put("valid", true);
            item.put("eventType", event.eventType());
            item.put("objectType", event.aggregateType());
            item.put("objectId", event.aggregateId());
            item.put("aggregateVersion", event.aggregateVersion());
            item.put("error", "投递或消费失败，修复原因后可重放");
        } catch (RuntimeException invalid) {/* 不向页面返回原消息载荷或解析异常。 */}
        return item;
    }

    public Map<String, Object> peek(AuthActor actor) {
        admin(actor);
        try {
            return rabbit.execute(channel -> {
                int total = channel.queueDeclarePassive(topics.deadQueue()).getMessageCount();
                var tags = new ArrayList<Long>();
                var items = new ArrayList<Map<String, Object>>();
                try {
                    for (int n = 0; n < 20; n++) {
                        var response = channel.basicGet(topics.deadQueue(), false);
                        if (response == null) break;
                        tags.add(response.getEnvelope().getDeliveryTag());
                        items.add(metadata(response));
                    }
                } finally {
                    for (long tag : tags) channel.basicNack(tag, false, true);
                }
                return Map.of("items", items, "readyCount", total, "previewLimit", 20);
            });
        } catch (RuntimeException brokerUnavailable) {
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "死信队列暂不可用");
        }
    }

    public Result replay(AuthActor actor, String key, String eventId, Replay input) {
        admin(actor);
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}") || eventId == null || !eventId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || input.reason() == null || input.reason().isBlank() || input.reason().length() > 500)
            throw new BusinessException(400, "VALIDATION_ERROR", "重放需要事件编号、幂等键和检查理由");
        String token = UUID.randomUUID().toString(), hash = EventTopics.digest(eventId + "\n" + input.reason());
        var lease = transactions.execute(tx -> {
            sql.update("INSERT INTO message_replay(actor_id,delivery_scope,request_key,event_id,payload_sha256,status,claim_token,lease_until,reason,trace_id) VALUES (?,?,?,?,?,'IN_PROGRESS',?,UTC_TIMESTAMP(3)+INTERVAL 30 SECOND,?,?) ON DUPLICATE KEY UPDATE id=id", Long.parseLong(actor.userId()), scope, key, eventId, hash, token, input.reason(), TraceIds.acceptOrCreate(org.slf4j.MDC.get(TraceIds.MDC_KEY)));
            var row = sql.queryForMap("SELECT *,lease_until>UTC_TIMESTAMP(3) AS leased FROM message_replay WHERE actor_id=? AND delivery_scope=? AND request_key=? FOR UPDATE", Long.parseLong(actor.userId()), scope, key);
            if (!hash.equals(row.get("payload_sha256")))
                throw new BusinessException(409, "IDEMPOTENCY_KEY_REUSED", "同一幂等键对应不同重放请求");
            if ("SUCCEEDED".equals(row.get("status"))) return row;
            if (!token.equals(row.get("claim_token")) && "IN_PROGRESS".equals(row.get("status")) && ((Number) row.get("leased")).intValue() == 1)
                throw new BusinessException(409, "REQUEST_IN_PROGRESS", "重放仍在处理中，请复用原键稍后重试");
            sql.update("UPDATE message_replay SET status='IN_PROGRESS',claim_token=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 30 SECOND WHERE id=?", token, row.get("id"));
            return row;
        });
        if ("SUCCEEDED".equals(lease.get("status")))
            return new Result(json.readValue(lease.get("response_body").toString(), Map.class), true);
        try {
            var result = rabbit.execute(channel -> {
                var skipped = new ArrayList<Long>();
                Long selected = null;
                boolean acked = false;
                try {
                    GetResponse target = null;
                    // 有界检索，预览未找到时应稍后重试，不允许无限扫描阻塞 HTTP 或消费线程。
                    for (int n = 0; n < 100; n++) {
                        var response = channel.basicGet(topics.deadQueue(), false);
                        if (response == null) break;
                        long tag = response.getEnvelope().getDeliveryTag();
                        if (eventId.equals(response.getProps().getMessageId())) {
                            target = response;
                            selected = tag;
                            break;
                        }
                        skipped.add(tag);
                    }
                    if (target == null)
                        throw new BusinessException(404, "NOT_FOUND", "当前可检索死信中没有该事件，请刷新检查");
                    BookingEvent event;
                    try {
                        if (target.getBody().length > 65536) throw new IllegalArgumentException("消息过大");
                        event = json.readValue(target.getBody(), BookingEvent.class);
                        event.validate(scope);
                    } catch (RuntimeException invalid) {
                        throw new BusinessException(422, "INVALID_EVENT", "消息契约无效，修复原因后再重放");
                    }
                    if (!eventId.equals(event.eventId()))
                        throw new BusinessException(422, "INVALID_EVENT", "消息编号不一致，不能直接重放");
                    var properties = new MessageProperties();
                    properties.setMessageId(eventId);
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    var correlation = new CorrelationData(eventId + "-replay-" + token);
                    rabbit.send(topics.exchange(), "changed", new Message(target.getBody(), properties), correlation);
                    if (!correlation.getFuture().get(3000, TimeUnit.MILLISECONDS).ack() || correlation.getReturned() != null)
                        throw new BusinessException(503, "SERVICE_UNAVAILABLE", "重放发布未确认");
                    var data = Map.<String, Object>of("eventId", eventId, "status", "REPUBLISHED");
                    int saved = sql.update("UPDATE message_replay SET status='SUCCEEDED',response_body=? WHERE id=? AND claim_token=?", json.writeValueAsString(data), lease.get("id"), token);
                    if (saved != 1) throw new BusinessException(409, "REQUEST_IN_PROGRESS", "重放租约已变化，请刷新");
                    channel.basicAck(selected, false);
                    acked = true;
                    return data;
                } catch (BusinessException rejected) {
                    throw rejected;
                } catch (IllegalArgumentException invalid) {
                    throw new BusinessException(422, "INVALID_EVENT", "消息契约无效，修复原因后再重放");
                } catch (Exception unavailable) {
                    throw new BusinessException(503, "SERVICE_UNAVAILABLE", "重放暂未完成，请复用原键重试");
                } finally {
                    if (selected != null && !acked) channel.basicNack(selected, false, true);
                    for (long tag : skipped) channel.basicNack(tag, false, true);
                }
            });
            return new Result(result, false);
        } catch (RuntimeException failure) {
            // 已发布并保存成功但 ACK 丢失时保持成功记录；原死信可能再次出现，消费仍按 eventId 去重。
            sql.update("UPDATE message_replay SET status='FAILED' WHERE id=? AND claim_token=? AND status='IN_PROGRESS'", lease.get("id"), token);
            if (failure instanceof BusinessException business) throw business;
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "死信重放暂不可用");
        }
    }
}
