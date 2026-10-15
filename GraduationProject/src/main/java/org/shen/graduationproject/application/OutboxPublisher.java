package org.shen.graduationproject.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shen.platform.contracts.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 短事务认领、事务外发送、确认后按租约 token 条件更新：不在预约资源锁内等待 RabbitMQ。
 * 允许发送成功但确认丢失后的重投；通知端按 eventId 去重，SENT 不等于用户已读或推送已送达。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher implements ApplicationRunner {
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final EventTopics topics;
    private final ObjectMapper json;
    @Value("${JWT_AUDIENCE}")
    private String scope;
    @Value("${BOOKING_OUTBOX_ENABLED:true}")
    private boolean enabled;
    @Value("${BOOKING_ADOPT_LEGACY_EVENTS:false}")
    private boolean adopt;
    @Value("${OUTBOX_MAX_ATTEMPTS:5}")
    private int maximum;
    @Value("${OUTBOX_CONFIRM_TIMEOUT_MS:3000}")
    private long confirmTimeout;
    @Value("${OUTBOX_RETRY_BASE_MS:1000}")
    private long backoff;
    @Value("${OUTBOX_LEASE_SECONDS:30}")
    private int lease;

    @Override
    public void run(ApplicationArguments args) {
        if (maximum < 1 || maximum > 20 || lease < 10 || lease > 300 || confirmTimeout < 100 || confirmTimeout > 10000 || backoff < 100 || backoff > 60000)
            throw new IllegalArgumentException("Outbox 重试与租约配置无效");
        if (enabled && adopt)
            sql.update("UPDATE outbox_event SET delivery_scope=? WHERE delivery_scope='legacy'", scope);
    }

    /**
     * 每次只认领一笔，避免串行发送一批时后面的租约在发送前已经过期。
     */
    private Map<String, Object> claim(boolean parking) {
        return transactions.execute(tx -> {
            String where = parking ? "status='FAILED' AND dead_lettered_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(3) AND (claim_expires_at IS NULL OR claim_expires_at<=UTC_TIMESTAMP(3))"
                    : "((status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='SENDING' AND claim_expires_at<=UTC_TIMESTAMP(3)))";
            var rows = sql.queryForList("SELECT * FROM outbox_event WHERE delivery_scope=? AND " + where + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", scope);
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            String token = UUID.randomUUID().toString();
            if (!parking && ((Number) row.get("attempts")).intValue() >= maximum) {
                sql.update("UPDATE outbox_event SET status='FAILED',last_error_code='LEASE_RETRY_EXHAUSTED',claim_token=NULL,claim_expires_at=NULL,next_attempt_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?", row.get("id"));
                return null;
            }
            sql.update("UPDATE outbox_event SET status=?,claim_token=?,claim_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),attempts=attempts+?,dead_letter_attempts=dead_letter_attempts+?,version=version+1 WHERE id=?", parking ? "FAILED" : "SENDING", token, lease, parking ? 0 : 1, parking ? 1 : 0, row.get("id"));
            row.put("claim_token", token);
            row.put("attempts", ((Number) row.get("attempts")).intValue() + (parking ? 0 : 1));
            return row;
        });
    }

    private void publish(Map<String, Object> row, boolean parking) throws Exception {
        var body = new LinkedHashMap<>(json.readValue(row.get("payload").toString(), Map.class));
        body.put("deliveryScope", scope);
        if (!parking) json.convertValue(body, BookingEvent.class).validate(scope);
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(row.get("event_id").toString());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("X-Trace-Id", TraceIds.acceptOrCreate(Objects.toString(body.get("traceId"), null)));
        if (parking)
            properties.setHeader("failure-code", Objects.toString(row.get("last_error_code"), "DELIVERY_FAILED"));
        var correlation = new CorrelationData(row.get("event_id") + "-" + row.get("claim_token"));
        rabbit.send(parking ? topics.deadExchange() : topics.exchange(), parking ? "parking" : "changed", new Message(json.writeValueAsBytes(body), properties), correlation);
        var confirmation = correlation.getFuture().get(confirmTimeout, TimeUnit.MILLISECONDS);
        if (!confirmation.ack() || correlation.getReturned() != null)
            throw new IllegalStateException("BROKER_NOT_ROUTED_OR_CONFIRMED");
    }

    private void deliver(Map<String, Object> row, boolean parking) {
        try {
            publish(row, parking);
            sql.update("UPDATE outbox_event SET status=?,published_at=IF(?,published_at,UTC_TIMESTAMP(3)),dead_lettered_at=IF(?,UTC_TIMESTAMP(3),dead_lettered_at),claim_token=NULL,claim_expires_at=NULL,version=version+1 WHERE id=? AND claim_token=?", parking ? "FAILED" : "SENT", parking, parking, row.get("id"), row.get("claim_token"));
        } catch (Exception failure) {
            String error = failure instanceof IllegalArgumentException ? "INVALID_EVENT" : "BROKER_DELIVERY_UNAVAILABLE";
            boolean exhausted = !parking && ((Number) row.get("attempts")).intValue() >= maximum;
            long delay = parking ? Math.max(backoff, 10000) : Math.min(60000, backoff * (1L << Math.min(10, ((Number) row.get("attempts")).intValue() - 1)));
            try {
                sql.update("UPDATE outbox_event SET status=?,last_error_code=?,next_attempt_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3)),claim_token=NULL,claim_expires_at=NULL,version=version+1 WHERE id=? AND claim_token=?", parking || exhausted ? "FAILED" : "PENDING", error, exhausted ? 0 : delay * 1000, row.get("id"), row.get("claim_token"));
            } catch (RuntimeException databaseUnavailable) {
                log.warn("事件租约等待恢复，eventId={}", row.get("event_id"));
            }
            log.warn("事件投递等待重试或人工处理，eventId={}，原因={}", row.get("event_id"), error);
        }
    }

    @Scheduled(initialDelayString = "${OUTBOX_INITIAL_DELAY_MS:3000}", fixedDelayString = "${OUTBOX_DELAY_MS:1000}")
    public void tick() {
        if (!enabled) return;
        try {
            for (int n = 0; n < 10; n++) {
                var row = claim(false);
                if (row == null) break;
                deliver(row, false);
            }
            for (int n = 0; n < 5; n++) {
                var row = claim(true);
                if (row == null) break;
                deliver(row, true);
            }
        } catch (RuntimeException failure) {
            log.warn("Outbox 扫描暂不可用，异常类型={}", failure.getClass().getSimpleName());
        }
    }
}
