package org.shen.platform.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * 网关与业务服务共用的 JWT 声明校验规则。
 * 签名验真由各自的解码器完成；此处进一步检查签发者、受众、有效时间和身份字段。
 * 令牌通过此处检查后，业务服务仍须确认数据库中的账号和会话是否有效。
 */
public final class JwtValidation {
    private JwtValidation() {
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("JWT issuer/audience required");
        }
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                // 校验版本为非负整数，用户与会话 ID 必须为可解析的正整数；字段缺失也视为无效令牌。
                Number rawVersion = jwt.getClaim("auth_version");
                long version = rawVersion.longValue();
                long userId = Long.parseLong(jwt.getSubject());
                long sessionId = Long.parseLong(jwt.<String>getClaim("sid"));
                boolean valid = jwt.getAudience().contains(audience)
                        && jwt.getSubject().matches("[1-9][0-9]{0,18}")
                        && jwt.<String>getClaim("sid").matches("[1-9][0-9]{0,18}")
                        && userId > 0 && sessionId > 0 && version >= 0 && rawVersion.doubleValue() == version && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                        && !jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(5))
                        && jwt.getExpiresAt().isAfter(jwt.getIssuedAt());
                if (valid) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) {
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
        };
        // 时间戳不设置额外过期宽限；签发时间最多容忍未来 5 秒的轻微时钟差。
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer), claims);
    }
}
