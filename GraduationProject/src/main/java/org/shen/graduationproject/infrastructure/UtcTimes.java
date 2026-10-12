package org.shen.graduationproject.infrastructure;

import java.sql.Timestamp;
import java.time.*;

/**
 * MySQL DATETIME 不携带偏移；统一按 UTC 解释，避免依赖主机默认时区。
 */
public final class UtcTimes {
    private UtcTimes() {
    }

    /**
     * 兼容 JDBC 返回 Timestamp 或 LocalDateTime 两种形态；后者没有时区，明确按 UTC 转换。
     */
    public static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("无法读取数据库 UTC 时间");
    }
}
