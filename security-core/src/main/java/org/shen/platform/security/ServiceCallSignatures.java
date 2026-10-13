package org.shen.platform.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 后台只读资格查询的服务签名：独立密钥，不使用身份签名私钥或用户访问令牌。
 * 签名绑定部署 audience、GET 路径、时间与一次性 nonce，服务端另用 Redis 防重放。
 */
public final class ServiceCallSignatures {
    public static final String TIME="X-Service-Time", NONCE="X-Service-Nonce", SIGNATURE="X-Service-Signature";
    private ServiceCallSignatures() {}

    public static byte[] readKey(String path) {
        try {
            var bytes=Files.readAllBytes(Path.of(path));
            if(bytes.length!=32)throw new IllegalArgumentException("服务调用密钥必须为 32 字节");
            return bytes;
        } catch(java.io.IOException failure) { throw new IllegalStateException("无法读取服务调用密钥",failure); }
    }

    public static String sign(byte[] key,String audience,String path,String time,String nonce) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("GET\n"+audience+"\n"+path+"\n"+time+"\n"+nonce).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException failure) { throw new IllegalStateException("无法生成服务调用签名",failure); }
    }

    public static Map<String,String> headers(byte[] key,String audience,String path) {
        String time=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        return Map.of(TIME,time,NONCE,nonce,SIGNATURE,sign(key,audience,path,time,nonce));
    }

    /** 限制时间、字符及长度，再常量时间比较；nonce 的“只能用一次”由服务端实现。 */
    public static boolean valid(byte[] key,String audience,String path,String time,String nonce,String signature,Instant now) {
        if(time==null||nonce==null||signature==null||!time.matches("[0-9]{1,12}")
                ||!nonce.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!signature.matches("[a-f0-9]{64}"))return false;
        if(Math.abs(now.getEpochSecond()-Long.parseLong(time))>10)return false;
        return MessageDigest.isEqual(sign(key,audience,path,time,nonce).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
    }
}
