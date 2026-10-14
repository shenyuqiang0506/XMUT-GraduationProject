package org.shen.ai.infrastructure;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 环境校验不启动 prod、不访问数据库；纯管理员不能借模型获得申请人的权限。 */
class AiEnvironmentTests {
    private final AuthActor student=new AuthActor("1","1","test","测试","ACTIVE",0,List.of("STUDENT"));
    @Test void prodCannotCreateDemoConversationEvenWithDevProfile() {
        var environment=new MockEnvironment();environment.setActiveProfiles("prod","dev");
        var repo=new AiRepository(null,null,new ObjectMapper(),environment,20);
        assertThatThrownBy(() -> repo.create(student,"test-key","DEMO"))
            .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(403));
    }
    @Test void teacherIsApplicantButManagementRoleAloneIsNot() {
        AiRepository.applicant(new AuthActor("2","2","teacher","教师","ACTIVE",0,List.of("TEACHER")));
        assertThatThrownBy(() -> AiRepository.applicant(new AuthActor("3","3","admin","管理","ACTIVE",0,List.of("SYSTEM_ADMIN"))))
            .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(403));
    }
    @Test void listCannotExposeDemoWithoutExplicitDevOrTestEnvironment() {
        for(String[] profiles:List.of(new String[]{"business"},new String[]{"prod","dev"})) {
            var environment=new MockEnvironment();environment.setActiveProfiles(profiles);
            var sql=mock(org.springframework.jdbc.core.JdbcTemplate.class);
            new AiRepository(sql,null,new ObjectMapper(),environment,20).list(student);
            verify(sql).queryForList(contains("data_mode='REAL'"),eq(1L));
        }
    }
}
