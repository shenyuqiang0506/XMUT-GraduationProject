package org.shen.identity.infrastructure;

import java.time.*;

import jakarta.servlet.http.HttpServletRequest;
import org.shen.platform.security.*;
import org.shen.platform.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 只有签名、用途和一次性 nonce 全部验证通过才采用网关报告的来源。
 * 无签名的直连请求继续按 TCP 对端限流；出现残缺或无效签名不能降级为“可信转发”。
 */
@Component
@Profile("business")
public class LoginPeerResolver {
    private final byte[] key;
    private final String audience;
    private final StringRedisTemplate redis;

    public LoginPeerResolver(StringRedisTemplate redis, @Value("${LOGIN_PEER_KEY_PATH}") String path, @Value("${JWT_AUDIENCE}") String audience) {
        this.redis = redis;
        this.audience = audience;
        key = ServiceCallSignatures.readKey(path);
    }

    public String resolve(HttpServletRequest request) {
        if (LoginPeerSignatures.HEADERS.stream().noneMatch(header -> request.getHeader(header) != null))
            return request.getRemoteAddr();
        String peer = request.getHeader(LoginPeerSignatures.PEER), nonce = request.getHeader(LoginPeerSignatures.NONCE);
        if (!"POST".equals(request.getMethod()) || !"/auth/login".equals(request.getRequestURI()) || request.getQueryString() != null
                || !LoginPeerSignatures.valid(key, audience, peer, request.getHeader(LoginPeerSignatures.TIME), nonce,
                request.getHeader(LoginPeerSignatures.SIGNATURE), Instant.now()))
            throw new BusinessException(401, "INVALID_LOGIN_SOURCE", "登录来源签名无效");
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("auth:" + audience + ":login:nonce:" + nonce, "1", Duration.ofSeconds(30))))
                throw new BusinessException(401, "INVALID_LOGIN_SOURCE", "登录来源签名已使用");
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "登录保护暂不可用，请稍后重试");
        }
        return peer;
    }
}
