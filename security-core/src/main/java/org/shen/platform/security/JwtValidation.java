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

public final class JwtValidation {
    private JwtValidation() {
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("JWT issuer/audience required");
        }
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
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
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer), claims);
    }
}
