package org.shen.platform.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 身份库、预约库共用的过期技术记录清理器，不删除账号、会话、业务资料和审计。
 * 每次最多五批、每批 500 行，每条 DELETE 独立提交，避免大事务长时间占用锁。
 * 多实例执行时由数据库行锁协调；仅成功且已过期的幂等记录允许删除。
 */
@Slf4j
public final class ExpiredRecordCleaner {
    private final JdbcTemplate sql;
    private final boolean identity;

    public ExpiredRecordCleaner(JdbcTemplate sql, boolean identity) {
        this.sql = sql;
        this.identity = identity;
    }

    @Scheduled(initialDelayString = "${RECORD_CLEANUP_INITIAL_DELAY_MS:60000}",
            fixedDelayString = "${RECORD_CLEANUP_DELAY_MS:300000}")
    public void clean() {
        try {
            int removed = remove("DELETE FROM idempotency_record WHERE status='SUCCEEDED' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at,id LIMIT 500");
            // 历史刷新摘要必须至少保留至会话到期，不能按轮换时间提前删除。
            if (identity)
                removed += remove("DELETE FROM used_refresh_token WHERE expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at,token_hash LIMIT 500");
            if (removed > 0) log.info("已清理过期技术记录，count={}", removed);
        } catch (RuntimeException failure) {
            // 一次清理失败不阻止后续调度，也不能影响正在处理的业务请求。
            log.error("过期技术记录清理失败，将在下一轮重试", failure);
        }
    }

    private int remove(String statement) {
        int total = 0;
        for (int batch = 0; batch < 5; batch++) {
            int count = sql.update(statement);
            total += count;
            if (count < 500) break;
        }
        return total;
    }
}
