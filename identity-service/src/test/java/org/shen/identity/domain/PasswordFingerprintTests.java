package org.shen.identity.domain;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.shen.identity.infrastructure.AccessTokens;
import static org.assertj.core.api.Assertions.*;

/** 独立指纹密钥必须兼容迁移前的指纹，并在 JWT 密钥轮换后保持稳定。 */
class PasswordFingerprintTests {
    @TempDir static Path directory;
    private static Path oldPrivate, oldPublic, newPrivate, newPublic, fingerprint;
    private static byte[] legacy;

    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var first = generator.generateKeyPair(); var second = generator.generateKeyPair();
        oldPrivate = pem("old-private.pem", "PRIVATE KEY", first.getPrivate().getEncoded());
        oldPublic = pem("old-public.pem", "PUBLIC KEY", first.getPublic().getEncoded());
        newPrivate = pem("new-private.pem", "PRIVATE KEY", second.getPrivate().getEncoded());
        newPublic = pem("new-public.pem", "PUBLIC KEY", second.getPublic().getEncoded());
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("xmut-identity-idempotency-password-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        legacy = digest.digest(first.getPrivate().getEncoded());
        fingerprint = directory.resolve("fingerprint.key"); Files.write(fingerprint, legacy);
    }

    private static Path pem(String filename, String kind, byte[] bytes) throws Exception {
        Path file = directory.resolve(filename);
        Files.writeString(file, "-----BEGIN " + kind + "-----\n" + Base64.getEncoder().encodeToString(bytes)
                + "\n-----END " + kind + "-----\n");
        return file;
    }

    private AccessTokens tokens(Path privateKey, Path publicKey) {
        return new AccessTokens(privateKey.toString(), publicKey.toString(), fingerprint.toString(), "test", "test", "key1", 900);
    }

    @Test void migratedKeyPreservesExistingFingerprint() throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(legacy, "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("测试密码Aa1!".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(tokens(oldPrivate, oldPublic).passwordFingerprint("测试密码Aa1!")).isEqualTo(expected);
    }

    @Test void rotatingJwtDoesNotChangePasswordFingerprint() {
        assertThat(tokens(oldPrivate, oldPublic).passwordFingerprint("测试密码Aa1!"))
                .isEqualTo(tokens(newPrivate, newPublic).passwordFingerprint("测试密码Aa1!"));
    }

    @Test void invalidIndependentKeyFailsAtStartup() throws Exception {
        Path bad = directory.resolve("bad.key"); Files.write(bad, new byte[8]);
        assertThatThrownBy(() -> new AccessTokens(oldPrivate.toString(), oldPublic.toString(), bad.toString(), "test", "test", "key1", 900))
                .isInstanceOf(IllegalStateException.class);
    }
}
