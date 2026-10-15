package org.shen.graduationproject;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.application.*;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 混合历史记录直接交给正式列表 SQL；校验模式过滤发生在统计/分页前并保留本人和审批组范围。 */
class BusinessListModeTests {
    private JdbcTemplate sql;
    private BookingPolicies policies;
    private final AuthActor student=actor("1","STUDENT"),system=actor("9","SYSTEM_ADMIN"),manager=actor("9","RESOURCE_ADMIN");
    private static AuthActor actor(String id,String role){return new AuthActor(id,"1","test","测试","ACTIVE",0,List.of(role));}
    @BeforeEach void mixedHistory() {
        sql=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        policies=mock(BookingPolicies.class);when(policies.now()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        sql.execute("CREATE TABLE activity_application(id BIGINT,applicant_id BIGINT,activity_name VARCHAR(200),status VARCHAR(32),version INT,participant_count INT,data_mode VARCHAR(16),start_at TIMESTAMP,end_at TIMESTAMP,hold_expires_at TIMESTAMP,policy_version INT,approval_group_id BIGINT,last_reason VARCHAR(500),updated_at TIMESTAMP)");
        sql.execute("CREATE TABLE approval_group(id BIGINT,status VARCHAR(16))");
        sql.execute("CREATE TABLE approval_group_member(group_id BIGINT,user_id BIGINT)");
        sql.execute("INSERT INTO approval_group VALUES(1,'ACTIVE'),(2,'ACTIVE')");
        sql.execute("INSERT INTO approval_group_member VALUES(1,9)");
        sql.execute("CREATE TABLE waitlist_group(id BIGINT,start_at TIMESTAMP,end_at TIMESTAMP,data_mode VARCHAR(16))");
        sql.execute("CREATE TABLE waitlist_entry(id BIGINT,group_id BIGINT,applicant_id BIGINT,activity_name VARCHAR(200),status VARCHAR(24),version INT,participant_count INT,expires_at TIMESTAMP,offer_expires_at TIMESTAMP,application_id BIGINT,last_reason VARCHAR(500),updated_at TIMESTAMP)");
        var start=Timestamp.from(Instant.parse("2026-10-15T06:00:00Z"));var end=Timestamp.from(Instant.parse("2026-10-15T07:00:00Z"));
        for(int id=1;id<=4;id++) {
            String mode=id%2==0?"DEMO":"REAL";long owner=id<=2?1:2,group=id<=2?1:2;
            sql.update("INSERT INTO activity_application VALUES(?,?,?,'CANCELLED',1,6,?,?,?,NULL,1,?,NULL,?)",id,owner,mode+"活动",mode,start,end,group,start);
            sql.update("INSERT INTO waitlist_group VALUES(?,?,?,?)",id,start,end,mode);
            sql.update("INSERT INTO waitlist_entry VALUES(?,?,?,?,'CANCELLED',1,6,?,NULL,NULL,NULL,?)",id,id,owner,mode+"候补",start,start);
        }
    }
    private DataModes modes(String...profiles){var environment=new MockEnvironment();environment.setActiveProfiles(profiles);return new DataModes(environment);}
    private Applications applications(DataModes modes) {
        return new Applications(sql,null,null,policies,null,new CatalogAccess(sql,modes),null,new ObjectMapper(),null,null,modes);
    }
    private Waitlists waitlists(DataModes modes) {
        return new Waitlists(sql,null,null,null,policies,null,null,null,new CatalogAccess(sql,modes),new ObjectMapper(),null,modes);
    }
    private List<String> ids(Map<String,Object> page,String key){return ((List<?>)page.get("items")).stream().map(item -> ((Map<?,?>)item).get(key).toString()).toList();}
    @Test void productionApplicantListFiltersBeforeTotalAndPaging() {
        var app=applications(modes("business","prod"));var first=app.list(student,false,"CANCELLED",1,1);
        assertThat(first).containsEntry("total",1L);assertThat(ids(first,"applicationId")).containsExactly("1");
        assertThat(ids(app.list(student,false,"",2,1),"applicationId")).isEmpty();
        assertThat(app.list(student,false,"REJECTED",1,20)).containsEntry("total",0L);
    }
    @Test void productionAdministrationKeepsSystemAndGroupScopes() {
        var app=applications(modes("prod"));var all=app.list(system,true,"",1,1);var scoped=app.list(manager,true,"",1,20);
        assertThat(all).containsEntry("total",2L);assertThat(ids(all,"applicationId")).containsExactly("3");
        assertThat(scoped).containsEntry("total",1L);assertThat(ids(scoped,"applicationId")).containsExactly("1");
        sql.update("UPDATE approval_group SET status='INACTIVE' WHERE id=1");
        assertThat(app.list(manager,true,"",1,20)).containsEntry("total",0L);
        assertThatThrownBy(() -> app.list(student,true,"",1,20)).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(403));
    }
    @Test void productionWaitlistFiltersGroupModeBeforeTotalAndPaging() {
        var waiting=waitlists(modes("prod"));var first=waiting.list(student,"CANCELLED",1,1);
        assertThat(first).containsEntry("total",1L);assertThat(ids(first,"waitlistId")).containsExactly("1");
        assertThat(ids(waiting.list(student,"",2,1),"waitlistId")).isEmpty();
        assertThat(waiting.list(student,"EXPIRED",1,20)).containsEntry("total",0L);
    }
    @Test void developmentListsStillIncludeBothModesWithinOwnerScope() {
        for(String profile:List.of("dev","test")) {
            var modes=modes(profile);var app=applications(modes).list(student,false,"",1,20);var waiting=waitlists(modes).list(student,"",1,20);
            assertThat(app).containsEntry("total",2L);assertThat(ids(app,"applicationId")).containsExactly("2","1");
            assertThat(waiting).containsEntry("total",2L);assertThat(ids(waiting,"waitlistId")).containsExactly("2","1");
        }
    }
    @Test void productionWinsOverDevelopmentAndUnspecifiedEnvironmentAlsoExcludesDemo() {
        for(var profiles:List.of(new String[]{"prod","dev","test"},new String[]{"business"})) {
            var modes=modes(profiles);
            assertThat(applications(modes).list(student,false,"",1,20)).containsEntry("total",1L);
            assertThat(waitlists(modes).list(student,"",1,20)).containsEntry("total",1L);
        }
    }
}
