package org.shen.graduationproject.application;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.*;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 只保证完全相同资源、时段和模式的组内优先，不承诺跨组合的全局 FIFO。
 */
@Component
@Profile("business")
@RequiredArgsConstructor
public class WaitlistPriority {
    private final JdbcTemplate sql;

    public void requireNoQueue(List<Long> ids, TimeWindow window, String mode) {
        long waiting = sql.queryForObject("SELECT COUNT(*) FROM waitlist_group g JOIN waitlist_entry w ON w.group_id=g.id WHERE g.group_key=? AND ((w.status='WAITING' AND w.expires_at>UTC_TIMESTAMP(3)) OR (w.status='OFFERED' AND w.offer_expires_at>UTC_TIMESTAMP(3)))", Long.class, ApplicationRequests.groupKey(ids, window, mode));
        if (waiting > 0)
            throw new BusinessException(409, "WAITLIST_PRIORITY", "同一组合和时段已有有效候补，请按候补顺序处理");
    }
}
