package org.shen.graduationproject.application;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.ApplicationCommands.Action;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 系统管理员检查当前部署的投递摘要，人工重试保留原 eventId，使已消费的消息仍能去重。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class OutboxAdministration {
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final CatalogIdempotency idempotency;
    @Value("${JWT_AUDIENCE}")
    private String scope;

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString());
        result.put("eventId", row.get("event_id"));
        result.put("eventType", row.get("event_type"));
        result.put("objectType", row.get("aggregate_type"));
        result.put("objectId", row.get("aggregate_id").toString());
        result.put("aggregateVersion", row.get("aggregate_version"));
        result.put("status", row.get("status"));
        result.put("attempts", row.get("attempts"));
        result.put("version", row.get("version"));
        result.put("lastErrorCode", row.get("last_error_code"));
        for (var pair : Map.of("nextAttemptAt", "next_attempt_at", "publishedAt", "published_at", "deadLetteredAt", "dead_lettered_at").entrySet())
            result.put(pair.getKey(), row.get(pair.getValue()) == null ? null : org.shen.graduationproject.domain.TimeWindow.display(UtcTimes.instant(row.get(pair.getValue()))));
        return result;
    }

    public Map<String, Object> list(AuthActor actor, String state, int page, int size) {
        access.systemAdministrator(actor);
        if (page < 1 || page > 100000 || size < 1 || size > 100 || (!state.isBlank() && !Set.of("PENDING", "SENDING", "SENT", "FAILED").contains(state)))
            throw new BusinessException(400, "VALIDATION_ERROR", "投递状态或分页无效");
        var args = new ArrayList<Object>();
        args.add(scope);
        String where = "delivery_scope=?";
        if (!state.isBlank()) {
            where += " AND status=?";
            args.add(state);
        }
        long total = sql.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE " + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        return Map.of("items", sql.queryForList("SELECT * FROM outbox_event WHERE " + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray()).stream().map(this::view).toList(), "total", total, "page", page, "size", size);
    }

    public CatalogIdempotency.Result retry(AuthActor actor, String key, long id, Action input) {
        access.systemAdministrator(actor);
        if (input.reason() == null || input.reason().isBlank())
            throw new BusinessException(400, "VALIDATION_ERROR", "人工重试需要填写检查理由");
        return idempotency.execute(actor, "OUTBOX_RETRY:" + id, key, input, () -> {
            var rows = sql.queryForList("SELECT * FROM outbox_event WHERE id=? AND delivery_scope=? FOR UPDATE", id, scope);
            if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "事件不存在或不在当前部署范围");
            var row = rows.getFirst();
            if (((Number) row.get("version")).intValue() != input.expectedVersion())
                throw new BusinessException(409, "VERSION_CONFLICT", "事件投递状态已变化，请刷新");
            if (!"FAILED".equals(row.get("status")))
                throw new BusinessException(409, "INVALID_STATE", "只有失败事件可人工重试");
            sql.update("UPDATE outbox_event SET status='PENDING',attempts=0,last_error_code=NULL,next_attempt_at=UTC_TIMESTAMP(3),claim_token=NULL,claim_expires_at=NULL,dead_lettered_at=NULL,version=version+1 WHERE id=?", id);
            sql.update("INSERT INTO audit_log(actor_id,action,object_type,object_id,from_status,to_status,reason,trace_id) VALUES (?,'OUTBOX_RETRY','OUTBOX',?,'FAILED','PENDING',?,?)", Long.parseLong(actor.userId()), id, input.reason(), org.shen.platform.contracts.TraceIds.acceptOrCreate(org.slf4j.MDC.get(org.shen.platform.contracts.TraceIds.MDC_KEY)));
            return new CatalogIdempotency.Result(200, view(sql.queryForMap("SELECT * FROM outbox_event WHERE id=?", id)), false);
        });
    }
}
