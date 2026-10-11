package org.shen.platform.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class JwtKeys {
    private JwtKeys() {
    }

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
