package org.shen.graduationproject.infrastructure;

import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Supplier;

import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 目录、可用性与联合预约写操作共用的幂等事务边界。
 * 操作者、操作名和请求键共同标识请求；请求指纹不同会冲突，相同且成功则回放原响应。
 * 业务回调与成功响应一起写入事务，抛错时整体回滚，避免出现成功记录却缺少业务写入。
 */
@Component
@Profile("business")
@RequiredArgsConstructor
public class CatalogIdempotency {
    public record Result(int status, Object data, boolean replayed) {
    }

    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final BookingWriteCoordinator coordinator;

    /**
     * 递归排序对象键，消除 JSON 字段顺序对指纹的影响；数组顺序保留，由用例决定是否另行规范化。
     */
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((k, v) -> ordered.put(k.toString(), canonical(v)));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    /**
     * 创建或锁定幂等记录，再执行回调或回放结果。
     * operation 应包含目标资源标识，使同一操作者对不同对象的请求互不混淆。
     */
    public Result execute(AuthActor actor, String operation, String key, Object payload, Supplier<Result> work) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}"))
            throw new BusinessException(400, "INVALID_IDEMPOTENCY_KEY", "需要 8–64 位幂等键");
        String hash = ImportStorage.hash(json.writeValueAsString(canonical(json.convertValue(payload, Object.class))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return transactions.execute(tx -> {
            sql.update("INSERT INTO idempotency_record(actor_id,operation,request_key,payload_hash,status,expires_at) VALUES (?,?,?,?,'IN_PROGRESS',UTC_TIMESTAMP(3)+INTERVAL 1 DAY) ON DUPLICATE KEY UPDATE id=id", Long.parseLong(actor.userId()), operation, key, hash);
            var row = sql.queryForMap("SELECT *,expires_at<=UTC_TIMESTAMP(3) AS expired FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE", Long.parseLong(actor.userId()), operation, key);
            // 在行锁内重置已过期的成功记录。即使清理任务尚未运行，一天有效期也会生效。
            if ("SUCCEEDED".equals(row.get("status")) && ((Number) row.get("expired")).intValue() == 1) {
                sql.update("UPDATE idempotency_record SET payload_hash=?,status='IN_PROGRESS',http_status=NULL,response_body=NULL,expires_at=UTC_TIMESTAMP(3)+INTERVAL 1 DAY WHERE id=?", hash, row.get("id"));
                row.put("payload_hash", hash);
                row.put("status", "IN_PROGRESS");
            }
            if (!hash.equals(row.get("payload_hash")))
                throw new BusinessException(409, "IDEMPOTENCY_KEY_REUSED", "同一幂等键对应的请求内容不同");
            if ("SUCCEEDED".equals(row.get("status")))
                return new Result(((Number) row.get("http_status")).intValue(), json.readValue(row.get("response_body").toString(), Object.class), true);
            coordinator.lock();
            // 回调在当前数据库事务内运行；成功响应与业务数据一并持久化，重试无需重新执行写操作。
            Result result = work.get();
            sql.update("UPDATE idempotency_record SET status='SUCCEEDED',http_status=?,response_body=? WHERE id=?", result.status(), json.writeValueAsString(result.data()), row.get("id"));
            return result;
        });
    }
}
