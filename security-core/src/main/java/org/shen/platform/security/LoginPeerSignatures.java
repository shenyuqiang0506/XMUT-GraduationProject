package org.shen.platform.security;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网关到身份服务的登录来源签名，使用独立的 32 字节密钥。
 * 绑定用途、POST 登录路径、环境、客户端 IP、时间及一次性 nonce；不授权任何用户身份。
 * 来源只取网关的 TCP 对端，客户端提交的 Forwarded/X-Forwarded-For 不参与签名。
 */
public final class LoginPeerSignatures {
    public static final String PEER = "X-Login-Peer", TIME = "X-Login-Time", NONCE = "X-Login-Nonce", SIGNATURE = "X-Login-Signature";
    public static final List<String> HEADERS = List.of(PEER, TIME, NONCE, SIGNATURE);

    private LoginPeerSignatures() {
    }

    /**
     * 只解析数字 IP，拒绝主机名以避免 DNS 查询和任意字符串进入来源计数键。
     */
    public static boolean numericAddress(String peer) {
        if (peer == null || peer.length() > 45) return false;
        if (peer.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
            return Arrays.stream(peer.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
        if (!peer.contains(":") || !peer.matches("[a-fA-F0-9:.]+")) return false;
        try {
            InetAddress.getByName(peer);
            return true;
        } catch (java.net.UnknownHostException invalid) {
            return false;
        }
    }

    public static String sign(byte[] key, String audience, String peer, String time, String nonce) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String message = "LOGIN_PEER_V1\nPOST\n/auth/login\n" + audience + "\n" + peer + "\n" + time + "\n" + nonce;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("无法签名登录来源", failure);
        }
    }

    public static Map<String, String> headers(byte[] key, String audience, String peer) {
        if (!numericAddress(peer)) throw new IllegalArgumentException("登录来源必须为数字 IP");
        String time = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
        return Map.of(PEER, peer, TIME, time, NONCE, nonce, SIGNATURE, sign(key, audience, peer, time, nonce));
    }

    public static boolean valid(byte[] key, String audience, String peer, String time, String nonce, String signature, Instant now) {
        if (!numericAddress(peer) || time == null || !time.matches("[0-9]{1,12}") || nonce == null
                || !nonce.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || signature == null || !signature.matches("[a-f0-9]{64}")) return false;
        if (Math.abs(now.getEpochSecond() - Long.parseLong(time)) > 10) return false;
        return MessageDigest.isEqual(sign(key, audience, peer, time, nonce).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }
}
