package org.shen.graduationproject;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.domain.*;
import org.shen.platform.support.BusinessException;
import static org.assertj.core.api.Assertions.*;

/** 同组身份固定，换场地、设备、时段或模式均不能继承另一组的排队优先级。 */
class WaitlistIdentityTests {
    private final TimeWindow window=new TimeWindow(Instant.parse("2026-10-14T00:00:00Z"),Instant.parse("2026-10-14T01:00:00Z"));
    @Test void memberOrderDoesNotChangeGroupButOtherDimensionsDo(){
        String key=ApplicationRequests.groupKey(List.of(1L,2L,3L),window,"REAL");
        assertThat(ApplicationRequests.groupKey(List.of(3L,1L,2L),window,"REAL")).isEqualTo(key);
        assertThat(ApplicationRequests.groupKey(List.of(1L,2L,4L),window,"REAL")).isNotEqualTo(key);
        assertThat(ApplicationRequests.groupKey(List.of(1L,2L,3L),window,"DEMO")).isNotEqualTo(key);
        assertThat(ApplicationRequests.groupKey(List.of(1L,2L,3L),new TimeWindow(window.start().plusSeconds(1),window.end()),"REAL")).isNotEqualTo(key);
    }
    @Test void duplicateResourceCannotBeHiddenByNormalization(){
        var input=new ApplicationCommands.Create("测试",null,1,window.start().atOffset(ZoneOffset.UTC),window.end().atOffset(ZoneOffset.UTC),"1",List.of("2","2"),Set.of());
        assertThatThrownBy(() -> ApplicationRequests.normalize(input)).isInstanceOf(BusinessException.class);
    }
}
