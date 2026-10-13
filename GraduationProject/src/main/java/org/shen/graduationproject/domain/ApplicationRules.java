package org.shen.graduationproject.domain;

import java.time.Instant;
import java.util.Set;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;

/** 与存储无关的角色、截止时间规则，便于边界测试；时间由调用方使用数据库时钟取得。 */
public final class ApplicationRules {
    private ApplicationRules() {}
    public static final Set<String> STATUSES = Set.of("PENDING_APPROVAL","APPROVED","CONFLICT_REVIEW",
            "COMPLETED","REJECTED","EXPIRED","CANCELLED");

    public static void applicant(AuthActor actor) {
        if (!actor.roles().contains("STUDENT") && !actor.roles().contains("TEACHER"))
            throw new BusinessException(403, "FORBIDDEN", "仅学生和教师可提交预约申请");
    }

    /** 暂占不能超过审批提前量截止，避免活动已开始时仍等待批准。 */
    public static Instant holdUntil(Instant now, Instant start, int ttlMinutes, int leadMinutes) {
        Instant deadline = start.minusSeconds(leadMinutes * 60L);
        Instant ttl = now.plusSeconds(ttlMinutes * 60L);
        Instant until = ttl.isBefore(deadline) ? ttl : deadline;
        if (!until.isAfter(now)) throw new BusinessException(400, "TIME_RANGE_INVALID", "已到预约提前量截止，无法创建待审批暂占");
        return until;
    }

    public static boolean canCancel(Instant now, Instant start, int leadMinutes) {
        return now.isBefore(start) && !now.isAfter(start.minusSeconds(leadMinutes * 60L));
    }
}
