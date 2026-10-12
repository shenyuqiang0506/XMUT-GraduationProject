package org.shen.identity.infrastructure;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        } // Caller must still verify MySQL; this is a deny-only cache.
    }

    public void afterCommit(long sid) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCommit() {
                try {
                    redis.opsForValue().set(prefix + sid, "1", Duration.ofSeconds(930));
                } catch (RuntimeException unavailable) { /* MySQL revocation remains authoritative. */ }
            }
        });
    }
}
