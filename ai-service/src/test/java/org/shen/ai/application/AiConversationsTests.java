package org.shen.ai.application;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shen.ai.domain.*;
import org.shen.ai.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实编排器不允许把两次独立业务查询的不同规则版本拼成一张可信卡片。 */
class AiConversationsTests {
    @Test void policyChangeBetweenReadsFailsWithoutLosingAcceptedConditions() {
        var repo=mock(AiRepository.class);var model=mock(ConditionExtractor.class);var tools=mock(BookingReadTools.class);
        var actor=new AuthActor("1","1","test","测试","ACTIVE",0,List.of("STUDENT"));
        var conditions=Map.<String,Object>of("date","2026-10-15","startLocal","14:00","durationMinutes",120,"participantCount",6,
            "requiredFeatures",List.of(),"equipmentRequirements",List.of());
        var claim=new AiRepository.Claim(1,1,"test-claim","DEMO",0,conditions,List.of(),Instant.parse("2026-10-14T00:00:00Z"),null);
        when(model.modelName()).thenReturn("test-model");when(repo.claim(any(),eq(1L),any(),anyString(),anyString())).thenReturn(claim);
        when(model.extract(anyString(),anyMap(),anyList(),anyMap(),any())).thenReturn(new AiCommands.Extraction(Map.of(),Map.of()));
        when(tools.options(anyString(),anyString(),anyString())).thenReturn(new BookingReadTools.Reply(Map.of("campuses",List.of(),"equipmentTypes",List.of()),"test-trace"));
        when(tools.rules(anyString(),anyString(),anyString())).thenReturn(new BookingReadTools.Reply(Map.of("configured",true,"version",1,"basis","规则一"),"test-trace"));
        var plan=Map.<String,Object>of("venueId","11","equipmentIds",List.of(),"resourceIds",List.of("11"),"approvalGroupId","1",
            "availability","AVAILABLE","coverageStatus","COMPLETE","dataMode","DEMO","policyVersion",2);
        when(tools.search(anyMap(),anyString(),anyString(),anyString())).thenReturn(new BookingReadTools.Reply(Map.of("items",List.of(plan),"checkedAt","2026-10-14T08:00:00+08:00"),"test-trace"));
        when(tools.details(anyList(),anyString(),anyString())).thenReturn(new BookingReadTools.Reply(List.of(Map.of("id","11","name","场地","resourceType","VENUE","dataMode","DEMO","approvalGroupId","1")),"test-trace"));
        when(repo.finish(any(),any(),anyMap())).thenAnswer(call -> call.getArgument(2));
        var service=new AiConversations(repo,model,new ConditionNormalizer(),tools);
        try {
            var result=service.send(actor,1,new AiCommands.Send("换一间","test-message",0),"test-token","test-trace").data();
            assertThat(result).containsEntry("status","FAILED").containsEntry("errorCode","RULES_CHANGED");
            assertThat(result.get("plans")).isEqualTo(List.of());
            assertThat(((Map<?,?>)result.get("conditions")).get("participantCount")).isEqualTo(6);
        } finally {service.stop();}
    }
}
