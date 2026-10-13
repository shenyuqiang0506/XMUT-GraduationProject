package org.shen.platform.security;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 签名必须绑定路径、环境和短时窗口；此处不代替真实接口的 Redis 防重放验收。 */
class ServiceCallSignaturesTests {
    private final byte[] key=new byte[32];private final Instant now=Instant.parse("2026-10-13T00:00:00Z");
    private final String time=Long.toString(now.getEpochSecond()),nonce=UUID.randomUUID().toString(),path="/internal/booking/applicants/1";
    @Test void validSignatureIsBoundToScopeAndPath(){
        String signature=ServiceCallSignatures.sign(key,"dev",path,time,nonce);
        assertThat(ServiceCallSignatures.valid(key,"dev",path,time,nonce,signature,now)).isTrue();
        assertThat(ServiceCallSignatures.valid(key,"prod",path,time,nonce,signature,now)).isFalse();
        assertThat(ServiceCallSignatures.valid(key,"dev",path+"0",time,nonce,signature,now)).isFalse();
    }
    @Test void staleAndFutureSignedRequestsAreRejected(){
        String signature=ServiceCallSignatures.sign(key,"dev",path,time,nonce);
        assertThat(ServiceCallSignatures.valid(key,"dev",path,time,nonce,signature,now.plusSeconds(11))).isFalse();
        assertThat(ServiceCallSignatures.valid(key,"dev",path,time,nonce,signature,now.minusSeconds(11))).isFalse();
    }
    @Test void malformedOrModifiedHeadersCannotAuthenticate(){
        String signature=ServiceCallSignatures.sign(key,"dev",path,time,nonce);
        assertThat(ServiceCallSignatures.valid(key,"dev",path,null,nonce,signature,now)).isFalse();
        assertThat(ServiceCallSignatures.valid(key,"dev",path,time,"bad",signature,now)).isFalse();
        assertThat(ServiceCallSignatures.valid(key,"dev",path,time,nonce,"0".repeat(64),now)).isFalse();
    }
}
