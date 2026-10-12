package org.shen.identity.infrastructure;

import java.util.List;

import org.shen.platform.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("business")
public class LoginThrottle {
    private final StringRedisTemplate redis;
    private final String prefix;
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);

    public LoginThrottle(StringRedisTemplate redis, @Value("${JWT_AUDIENCE}") String audience) {
        this.redis = redis;
        this.prefix = "auth:" + audience + ":login:";
    }

    public void check(String username, String peer) {
        try {
            Long ip = redis.execute(COUNT, List.of(prefix + "peer:" + TokenSecrets.hash(peer)), "60");
            Long account = redis.execute(COUNT, List.of(prefix + "account:" + TokenSecrets.hash(username)), "900");
            if (ip == null || account == null) throw new IllegalStateException();
            if (ip > 30 || account > 10)
                throw new BusinessException(429, "LOGIN_RATE_LIMITED", "登录尝试过多，请稍后重试");
        } catch (BusinessException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "登录保护暂不可用，请稍后重试");
        }
    }
}
