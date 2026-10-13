package org.shen.graduationproject;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.domain.ApplicationRules;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import static org.assertj.core.api.Assertions.*;

/** 截止时间采用闭合截止、活动开始严格排除；角色资格不能由“非学生”推断为管理员。 */
class ApplicationRulesTests {
    private final Instant now=Instant.parse("2027-03-10T00:00:00Z");
    @Test void holdIsCappedByApprovalDeadline() {
        assertThat(ApplicationRules.holdUntil(now,now.plusSeconds(7200),180,30)).isEqualTo(now.plusSeconds(5400));
        assertThat(ApplicationRules.holdUntil(now,now.plusSeconds(7200),30,30)).isEqualTo(now.plusSeconds(1800));
    }
    @Test void noHoldCanStartAtOrAfterItsDeadline() {
        assertThatThrownBy(() -> ApplicationRules.holdUntil(now,now.plusSeconds(1800),60,30)).isInstanceOf(BusinessException.class);
    }
    @Test void cancellationAllowsCutoffButNotActivityStart() {
        var start=now.plusSeconds(3600);
        assertThat(ApplicationRules.canCancel(now.plusSeconds(1800),start,30)).isTrue();
        assertThat(ApplicationRules.canCancel(now.plusSeconds(1801),start,30)).isFalse();
        assertThat(ApplicationRules.canCancel(start,start,0)).isFalse();
    }
    @Test void onlyExplicitStudentOrTeacherRoleCanCreate() {
        for(String role:List.of("STUDENT","TEACHER"))
            assertThatCode(() -> ApplicationRules.applicant(new AuthActor("1","1","编号","姓名","ACTIVE",0,List.of(role)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ApplicationRules.applicant(new AuthActor("1","1","编号","姓名","ACTIVE",0,List.of("RESOURCE_ADMIN"))))
                .isInstanceOf(BusinessException.class);
    }
}
