package org.shen.graduationproject.application;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.shen.platform.support.BusinessException;

/**
 * 用户配额包含有效申请与未到期 OFFERED；确认自己的 offer 时只转换 owner，不重复计数。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class ApplicantQuota {
    private final JdbcTemplate sql;

    public void lock(long user) {
        sql.update("INSERT INTO applicant_guard(user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_id", user);
        sql.queryForObject("SELECT user_id FROM applicant_guard WHERE user_id=? FOR UPDATE", Long.class, user);
    }

    public long active(long user, Long ownOffer) {
        long applications = sql.queryForObject("SELECT COUNT(*) FROM activity_application WHERE applicant_id=? AND (status='CONFLICT_REVIEW' OR (status='APPROVED' AND end_at>UTC_TIMESTAMP(3)) OR (status='PENDING_APPROVAL' AND hold_expires_at>UTC_TIMESTAMP(3)))", Long.class, user);
        long offers = sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE applicant_id=? AND status='OFFERED' AND offer_expires_at>UTC_TIMESTAMP(3) AND (? IS NULL OR id<>?)", Long.class, user, ownOffer, ownOffer);
        return applications + offers;
    }

    public void requireSpace(long user, int maximum, Long ownOffer) {
        if (active(user, ownOffer) >= maximum)
            throw new BusinessException(422, "APPLICATION_LIMIT_REACHED", "已达到有效申请及候补暂占额度");
    }
}
