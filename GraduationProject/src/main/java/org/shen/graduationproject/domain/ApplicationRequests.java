package org.shen.graduationproject.domain;

import java.time.ZoneOffset;
import java.util.*;

import org.shen.graduationproject.infrastructure.CatalogAccess;
import org.shen.platform.contracts.EventTopics;
import org.shen.platform.support.BusinessException;

/**
 * 直接申请和候补共用内容规范化，保留前端显式要求；资源数组的顺序不改变组合身份。
 */
public final class ApplicationRequests {
    private ApplicationRequests() {
    }

    public static ApplicationCommands.Create normalize(ApplicationCommands.Create input) {
        var equipment = input.equipmentIds() == null ? List.<String>of() : input.equipmentIds();
        long venue = CatalogAccess.id(input.venueId());
        var ids = equipment.stream().map(CatalogAccess::id).sorted().toList();
        if (new HashSet<>(ids).size() != ids.size() || ids.contains(venue))
            throw new BusinessException(400, "VALIDATION_ERROR", "场地和设备编号不能重复");
        var window = TimeWindow.of(input.startAt(), input.endAt());
        window.singleDay();
        return new ApplicationCommands.Create(input.activityName().trim(), input.description() == null ? null : input.description().trim(),
                input.participantCount(), window.start().atOffset(ZoneOffset.UTC), window.end().atOffset(ZoneOffset.UTC), Long.toString(venue),
                ids.stream().map(Object::toString).toList(), input.requiredFeatures() == null ? new TreeSet<>() : new TreeSet<>(input.requiredFeatures()));
    }

    public static List<Long> members(ApplicationCommands.Create input) {
        var ids = new TreeSet<Long>();
        ids.add(CatalogAccess.id(input.venueId()));
        input.equipmentIds().forEach(id -> ids.add(CatalogAccess.id(id)));
        return List.copyOf(ids);
    }

    public static String groupKey(List<Long> ids, TimeWindow window, String mode) {
        return EventTopics.digest("resources=" + ids.stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(","))
                + ";start=" + window.start() + ";end=" + window.end() + ";mode=" + mode);
    }
}
