package org.shen.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 高熵刷新凭据生成与摘要工具。
 * 刷新凭据采用 32 字节安全随机数，使用无填充的 URL 安全 Base64 编码。
 * SHA-256 用于随机令牌查找和请求指纹，不替代用户密码的 BCrypt 存储。
 */
public final class TokenSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private TokenSecrets() {}
    public static String refreshToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
