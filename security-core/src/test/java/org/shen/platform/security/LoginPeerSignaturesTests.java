package org.shen.platform.security;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 修改来源、环境或时间都不能复用签名；登录签名不能冒充后台资格查询签名。 */
class LoginPeerSignaturesTests {
    private final byte[] key=new byte[32];
    private final Instant now=Instant.parse("2026-10-14T00:00:00Z");
    private final String time=Long.toString(now.getEpochSecond()),nonce=UUID.randomUUID().toString();
    @Test void sourceAndEnvironmentAreBound() {
        String signature=LoginPeerSignatures.sign(key,"dev","192.0.2.1",time,nonce);
        assertThat(LoginPeerSignatures.valid(key,"dev","192.0.2.1",time,nonce,signature,now)).isTrue();
        assertThat(LoginPeerSignatures.valid(key,"dev","192.0.2.2",time,nonce,signature,now)).isFalse();
        assertThat(LoginPeerSignatures.valid(key,"prod","192.0.2.1",time,nonce,signature,now)).isFalse();
        assertThat(LoginPeerSignatures.valid(key,"dev","192.0.2.1",time,nonce,signature,now.plusSeconds(11))).isFalse();
        assertThat(ServiceCallSignatures.valid(key,"dev","/auth/login",time,nonce,signature,now)).isFalse();
    }
    @Test void addressMustBeNumericAndHeadersMustBeComplete() {
        assertThat(LoginPeerSignatures.numericAddress("localhost")).isFalse();
        assertThat(LoginPeerSignatures.numericAddress("256.0.0.1")).isFalse();
        assertThat(LoginPeerSignatures.numericAddress("::1")).isTrue();
        assertThat(LoginPeerSignatures.numericAddress("::ffff:192.0.2.1")).isTrue();
        assertThat(LoginPeerSignatures.numericAddress("[::1]")).isFalse();
        assertThat(LoginPeerSignatures.valid(key,"dev","127.0.0.1",null,nonce,"0".repeat(64),now)).isFalse();
    }
}
