package org.shen.identity.infrastructure;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 设备会话的撤销缓存，仅能提前拒绝，不能作为允许访问的依据。
 * 缓存未命中或 Redis 故障都必须继续检查 MySQL；写缓存只在撤销事务提交后发生。
 */
@Component
@Profile("business")
public class RevocationCache {
    private final StringRedisTemplate redis;
    private final String prefix;

    public RevocationCache(StringRedisTemplate redis, @Value("${JWT_AUDIENCE}") String audience) {
        this.redis = redis;
        this.prefix = "auth:" + audience + ":revoked:";
    }

    public boolean revoked(long sid) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(prefix + sid));
        } catch (RuntimeException unavailable) {
            return false;
        } // 调用方仍需验证 MySQL；该缓存只能拒绝，不能授权。
    }

    /**
     * 在事务成功提交后缓存撤销状态 930 秒，覆盖当前最多 900 秒的访问令牌寿命。
     */
    public void afterCommit(long sid) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCommit() {
                try {
                    redis.opsForValue().set(prefix + sid, "1", Duration.ofSeconds(930));
                } catch (RuntimeException unavailable) { /* 缓存失败不撤销数据库中已提交的会话撤销结果。 */ }
            }
        });
    }
}
