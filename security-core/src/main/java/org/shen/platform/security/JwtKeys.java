package org.shen.platform.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 读取部署环境指定的 RSA 密钥文件。
 * 公钥采用 X.509 编码，私钥采用 PKCS#8 编码；只支持不少于 2048 位的 RSA 密钥。
 * 失败消息不拼接文件内容，避免密钥材料进入异常响应。
 */
public final class JwtKeys {
    private JwtKeys() {
    }

    /**
     * 去除指定 PEM 头尾及空白后解码。这里只接受配置中的密钥路径，不接收业务请求传入的路径。
     */
    private static byte[] pem(String path, String type) {
        try {
            String text = Files.readString(Path.of(path));
            return Base64.getDecoder().decode(text.replace("-----BEGIN " + type + "-----", "")
                    .replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load configured JWT key");
        }
    }

    public static RSAPublicKey publicKey(String path) {
        try {
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(path, "PUBLIC KEY")));
            if (key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid JWT public key");
        }
    }

    public static RSAPrivateKey privateKey(String path) {
        try {
            var key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pem(path, "PRIVATE KEY")));
            if (key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid JWT private key");
        }
    }
}
