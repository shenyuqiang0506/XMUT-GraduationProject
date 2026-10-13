package org.shen.platform.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;

/** 验证积压数据不会使单轮任务无限运行，失败之后的下一轮仍能执行。 */
class ExpiredRecordCleanerTests {
    @Test void unlimitedBacklogIsBoundedToFiveBatchesPerTable() {
        var sql = mock(JdbcTemplate.class);
        when(sql.update(anyString())).thenReturn(500);
        new ExpiredRecordCleaner(sql, true).clean();
        verify(sql, times(10)).update(anyString());
    }

    @Test void failureDoesNotPreventNextScheduledRun() {
        var sql = mock(JdbcTemplate.class);
        when(sql.update(anyString())).thenThrow(new IllegalStateException("测试清理故障")).thenReturn(0);
        var cleaner = new ExpiredRecordCleaner(sql, false);
        cleaner.clean(); cleaner.clean();
        verify(sql, times(2)).update(anyString());
    }
}
