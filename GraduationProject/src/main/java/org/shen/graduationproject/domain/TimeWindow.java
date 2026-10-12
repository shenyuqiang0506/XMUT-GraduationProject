package org.shen.graduationproject.domain;

import java.time.*;
import java.util.Comparator;
import java.util.List;

import org.shen.platform.support.BusinessException;

/**
 * 使用半开区间 [start,end)，相接的两段不算占用冲突。
 */
public record TimeWindow(Instant start, Instant end) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 构造时统一约束非空、正长度及毫秒精度，所有导入和查询共用同一时间边界。
     */
    public TimeWindow {
        if (start == null || end == null || !start.isBefore(end)
                || start.getNano() % 1000000 != 0 || end.getNano() % 1000000 != 0)
            throw new BusinessException(400, "TIME_RANGE_INVALID", "时间需要带偏移、开始早于结束，精度不超过毫秒");
    }

    public static TimeWindow of(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null)
            throw new BusinessException(400, "TIME_RANGE_INVALID", "需要完整的起止时间");
        return new TimeWindow(start.toInstant(), end.toInstant());
    }

    /**
     * 包含允许两端相等；用于确认来源行是否完全位于声明窗口内。
     */
    public boolean contains(TimeWindow other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    /**
     * 严格比较两端，保证 09:00–10:00 与 10:00–11:00 不被判作相交。
     */
    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && end.isAfter(other.start);
    }

    public void limitDays(int maximum) {
        if (Duration.between(start, end).compareTo(Duration.ofDays(maximum)) > 0)
            throw new BusinessException(400, "TIME_RANGE_INVALID", "查询或导入范围过大");
    }

    /**
     * 以业务时区判断自然日；结束恰好为次日零点也会跨日，因此当前规则会拒绝。
     */
    public void singleDay() {
        if (!start.atZone(ZONE).toLocalDate().equals(end.atZone(ZONE).toLocalDate()))
            throw new BusinessException(400, "TIME_RANGE_INVALID", "一期预约不支持跨上海自然日");
    }

    public static String display(Instant value) {
        return value.atZone(ZONE).toOffsetDateTime().toString();
    }

    /**
     * 合并相接的证据区间；任何一个空隙都不能推断成完整覆盖。
     */
    public static boolean covered(TimeWindow target, List<TimeWindow> intervals) {
        // 游标表示已经连续证明到的位置。已落在游标之前的区间可跳过，出现缺口立即判不完整。
        Instant cursor = target.start;
        for (var interval : intervals.stream().sorted(Comparator.comparing(TimeWindow::start)).toList()) {
            if (!interval.end.isAfter(cursor)) continue;
            if (interval.start.isAfter(cursor)) return false;
            if (interval.end.isAfter(cursor)) cursor = interval.end;
            if (!cursor.isBefore(target.end)) return true;
        }
        return false;
    }
}
