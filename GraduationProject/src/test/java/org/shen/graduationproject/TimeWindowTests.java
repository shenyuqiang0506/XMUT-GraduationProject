package org.shen.graduationproject;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.platform.support.BusinessException;
import static org.assertj.core.api.Assertions.*;

class TimeWindowTests {
    private TimeWindow window(int start,int end) {
        return new TimeWindow(Instant.parse("2027-03-10T00:00:00Z").plusSeconds(start),
                Instant.parse("2027-03-10T00:00:00Z").plusSeconds(end));
    }
    @Test void adjacentWindowsDoNotConflictButOverlapDoes() {
        assertThat(window(0,60).overlaps(window(60,120))).isFalse();
        assertThat(window(0,60).overlaps(window(59,120))).isTrue();
    }
    @Test void adjacentEvidenceMayCoverAnIntervalButAnyGapRemainsUnknown() {
        assertThat(TimeWindow.covered(window(0,120),List.of(window(60,120),window(0,60)))).isTrue();
        assertThat(TimeWindow.covered(window(0,120),List.of(window(0,59),window(60,120)))).isFalse();
    }
    @Test void nestedAndOutOfRangeEvidenceCannotExtendItsActualCoverage() {
        assertThat(TimeWindow.covered(window(0,120),List.of(window(-10,140),window(20,30)))).isTrue();
        assertThat(TimeWindow.covered(window(0,120),List.of(window(-10,119),window(140,150)))).isFalse();
    }
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
