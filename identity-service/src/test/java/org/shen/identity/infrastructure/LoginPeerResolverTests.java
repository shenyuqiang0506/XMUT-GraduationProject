package org.shen.identity.infrastructure;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.shen.platform.security.LoginPeerSignatures;
import org.shen.platform.support.BusinessException;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实解析器与限流器配合测试，Redis 仅用独立内存计数替身，不访问日常登录键。 */
class LoginPeerResolverTests {
    @TempDir Path directory;
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final ValueOperations<String,String> values=mock(ValueOperations.class);
    private LoginPeerResolver peers;
    private LoginThrottle throttle;
    @BeforeEach void setup() throws Exception {
        Path path=directory.resolve("peer.key");Files.write(path,new byte[32]);
        peers=new LoginPeerResolver(redis,path.toString(),"isolated");throttle=new LoginThrottle(redis,"isolated");
        Set<String> nonces=new HashSet<>();Map<String,Long> counts=new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),eq("1"),any(java.time.Duration.class))).thenAnswer(call -> nonces.add(call.getArgument(0)));
        when(redis.execute(any(),anyList(),anyString())).thenAnswer(call -> {
            List<String> keys=call.getArgument(1);return counts.merge(keys.getFirst(),1L,Long::sum);
        });
    }
    private MockHttpServletRequest request(String peer) {
        var request=new MockHttpServletRequest("POST","/auth/login");request.setRemoteAddr("10.0.0.1");
        LoginPeerSignatures.headers(new byte[32],"isolated",peer).forEach(request::addHeader);return request;
    }
    @Test void distinctClientsDoNotShareGatewayBucket() {
        for(int i=1;i<=35;i++)throttle.check("account-"+i,peers.resolve(request("192.0.2."+i)));
    }
    @Test void oneClientAndOneAccountStillHaveSeparateLimits() {
        for(int i=0;i<30;i++)throttle.check("client-account-"+i,peers.resolve(request("192.0.2.1")));
        assertThatThrownBy(() -> throttle.check("fresh-account",peers.resolve(request("192.0.2.1"))))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.code()).isEqualTo("LOGIN_RATE_LIMITED"));
        for(int i=1;i<=10;i++)throttle.check("shared-account",peers.resolve(request("198.51.100."+i)));
        assertThatThrownBy(() -> throttle.check("shared-account",peers.resolve(request("198.51.100.11"))))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(429));
    }
    @Test void spoofedIncompleteAndReplayedHeadersAreRejected() {
        var valid=request("192.0.2.1");assertThat(peers.resolve(valid)).isEqualTo("192.0.2.1");
        assertThatThrownBy(() -> peers.resolve(valid)).isInstanceOf(BusinessException.class);
        var modified=request("192.0.2.1");modified.removeHeader(LoginPeerSignatures.PEER);modified.addHeader(LoginPeerSignatures.PEER,"192.0.2.99");
        assertThatThrownBy(() -> peers.resolve(modified)).isInstanceOf(BusinessException.class);
        var incomplete=new MockHttpServletRequest("POST","/auth/login");incomplete.addHeader(LoginPeerSignatures.PEER,"192.0.2.1");
        assertThatThrownBy(() -> peers.resolve(incomplete)).isInstanceOf(BusinessException.class);
    }
    @Test void directCallsIgnoreUnsignedForwardedHeaderAndRedisFailureClosesAccess() {
        var direct=new MockHttpServletRequest("POST","/auth/login");direct.setRemoteAddr("192.0.2.77");direct.addHeader("X-Forwarded-For","192.0.2.88");
        assertThat(peers.resolve(direct)).isEqualTo("192.0.2.77");
        when(values.setIfAbsent(anyString(),eq("1"),any(java.time.Duration.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("测试故障"));
        assertThatThrownBy(() -> peers.resolve(request("192.0.2.1"))).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(503));
    }
}
