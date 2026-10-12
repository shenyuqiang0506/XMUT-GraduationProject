package org.shen.graduationproject;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.platform.support.BusinessException;
import static org.assertj.core.api.Assertions.*;

/**
 * 时间窗口的边界测试，覆盖半开区间、连续覆盖、上海自然日及毫秒精度。
 * 所有样例为固定测试时间，不是用户提供的真实资源安排。
 */
class TimeWindowTests {
    private TimeWindow window(int start,int end) {
        return new TimeWindow(Instant.parse("2027-03-10T00:00:00Z").plusSeconds(start),
                Instant.parse("2027-03-10T00:00:00Z").plusSeconds(end));
    }
    /**
     * 相接端点允许连续使用，哪怕只重叠一秒也应判为冲突。
     */
    @Test void adjacentWindowsDoNotConflictButOverlapDoes() {
        assertThat(window(0,60).overlaps(window(60,120))).isFalse();
        assertThat(window(0,60).overlaps(window(59,120))).isTrue();
    }
    /**
     * 证据可以乱序输入并合并相接区间，但任何真实缺口都不能被自动补齐。
     */
    @Test void adjacentEvidenceMayCoverAnIntervalButAnyGapRemainsUnknown() {
        assertThat(TimeWindow.covered(window(0,120),List.of(window(60,120),window(0,60)))).isTrue();
        assertThat(TimeWindow.covered(window(0,120),List.of(window(0,59),window(60,120)))).isFalse();
    }
    @Test void nestedAndOutOfRangeEvidenceCannotExtendItsActualCoverage() {
        assertThat(TimeWindow.covered(window(0,120),List.of(window(-10,140),window(20,30)))).isTrue();
        assertThat(TimeWindow.covered(window(0,120),List.of(window(-10,119),window(140,150)))).isFalse();
    }
    /**
     * UTC 跨日未必上海跨日；反之结束落在上海次日零点也应被当前单日规则拒绝。
     */
    @Test void naturalDayIsDeterminedInShanghaiRatherThanUtc() {
        var sameDay=TimeWindow.of(OffsetDateTime.parse("2027-03-09T23:00:00Z"),OffsetDateTime.parse("2027-03-10T01:00:00Z"));
        assertThatCode(sameDay::singleDay).doesNotThrowAnyException();
        var nextDay=TimeWindow.of(OffsetDateTime.parse("2027-03-10T23:00:00+08:00"),OffsetDateTime.parse("2027-03-11T00:00:00+08:00"));
        assertThatThrownBy(nextDay::singleDay).isInstanceOf(BusinessException.class);
    }
    @Test void reversedEmptyAndSubMillisecondRangesAreRejected() {
        assertThatThrownBy(()->window(10,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->window(20,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new TimeWindow(Instant.EPOCH.plusNanos(1),Instant.EPOCH.plusSeconds(1))).isInstanceOf(BusinessException.class);
    }
    @Test void displayAlwaysIncludesShanghaiOffset() {
        assertThat(TimeWindow.display(Instant.parse("2027-03-10T00:00:00Z"))).isEqualTo("2027-03-10T08:00+08:00");
    }
}
