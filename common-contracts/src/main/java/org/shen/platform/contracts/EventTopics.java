package org.shen.platform.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 固定按部署 audience 隔离消息和缓存名称；测试与日常 dev 不共享队列或消费去重空间。
 */
public record EventTopics(String exchange, String queue, String deadExchange, String deadQueue) {
    public static EventTopics forScope(String scope) {
        String prefix = "campus." + digest(scope).substring(0, 20);
        return new EventTopics(prefix + ".events", prefix + ".notifications", prefix + ".dead", prefix + ".dead.notifications");
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
