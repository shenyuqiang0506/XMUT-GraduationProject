package org.shen.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.security.JwtKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
@Profile("business & !m1")
public class AccessTokens {
    private final JwtEncoder encoder;
    private final javax.crypto.SecretKey passwordFingerprintKey;
    private final String issuer,audience,keyId;
    private final long seconds;
    public AccessTokens(@Value("${JWT_PRIVATE_KEY_PATH}") String privatePath,@Value("${JWT_PUBLIC_KEY_PATH}") String publicPath,
            @Value("${JWT_ISSUER}") String issuer,@Value("${JWT_AUDIENCE}") String audience,
            @Value("${JWT_KEY_ID}") String keyId,@Value("${AUTH_ACCESS_TTL_SECONDS:900}") long seconds) {
        if(seconds<1 || seconds>900) throw new IllegalStateException("Access TTL must be 1..900 seconds");
        var privateKey=JwtKeys.privateKey(privatePath);
        var key=new RSAKey.Builder(JwtKeys.publicKey(publicPath)).privateKey(privateKey).keyID(keyId).build();
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update("xmut-identity-idempotency-password-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.passwordFingerprintKey=new javax.crypto.spec.SecretKeySpec(digest.digest(privateKey.getEncoded()),"HmacSHA256");
        } catch(java.security.GeneralSecurityException unavailable) { throw new IllegalStateException("Password fingerprint unavailable"); }
        this.encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        this.issuer=issuer;this.audience=audience;this.keyId=keyId;this.seconds=seconds;
    }
    public String passwordFingerprint(String password) {
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(passwordFingerprintKey);
            return java.util.HexFormat.of().formatHex(mac.doFinal(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException unavailable) { throw new IllegalStateException("Password fingerprint unavailable"); }
    }
    public record Tokens(String accessToken,String refreshToken,String tokenType,long expiresIn,String sessionId) {}
    public Tokens issue(AuthActor actor,String refresh) {
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer(issuer).subject(actor.userId()).audience(List.of(audience))
                .issuedAt(now).expiresAt(now.plusSeconds(seconds)).claim("sid",actor.sessionId())
                .claim("auth_version",actor.authVersion()).build();
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(),claims)).getTokenValue();
        return new Tokens(token,refresh,"Bearer",seconds,actor.sessionId());
    }
}
