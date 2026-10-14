package org.shen.identity.application;

import org.junit.jupiter.api.Test;
import org.shen.identity.domain.AuthCommands;
import org.shen.identity.infrastructure.*;
import org.shen.platform.support.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只有轮换事务开始之前的故障可以承诺“未执行”，不能用笼统 503 保证重试安全。 */
class RefreshFailureTests {
    @Test void lookupFailureIsExplicitlySafeToRetry() {
        var repo=mock(IdentityRepository.class);var encoder=mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("test-dummy");
        var transactions=mock(TransactionTemplate.class);var tokens=mock(AccessTokens.class);
        var authentication=new AuthenticationService(repo,encoder,tokens,mock(RevocationCache.class),transactions,3600);
        when(repo.findRefresh(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("测试断连"));
        assertThatThrownBy(() -> authentication.refresh(new AuthCommands.Refresh("test-refresh-token","test-device")))
                .isInstanceOfSatisfying(BusinessException.class,e -> {
                    assertThat(e.status()).isEqualTo(503);assertThat(e.code()).isEqualTo("REFRESH_NOT_EXECUTED");
                });
        verifyNoInteractions(transactions,tokens);
    }
}
