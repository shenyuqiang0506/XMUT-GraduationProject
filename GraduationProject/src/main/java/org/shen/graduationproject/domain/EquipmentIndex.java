package org.shen.graduationproject.domain;

import java.util.*;

/**
 * 可用设备按审批组、校区、类型建立索引，每个请求只构建一次。
 * 保留 SQL 按 ID 排序的输入顺序，使索引优化前后选中的设备保持一致。
 * 只接收已经通过开放规则、来源覆盖、外部占用校验的设备，不改变可用性判定。
 */
public final class EquipmentIndex {
    private record Key(Object group, Object campus, Object type) {}
    private final Map<Key, List<Map<String, Object>>> grouped = new HashMap<>();

    public EquipmentIndex(List<Map<String, Object>> usable) {
        for (var equipment : usable) {
            var key = new Key(equipment.get("approval_group_id"), equipment.get("campus"), equipment.get("equipment_type"));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(equipment);
        }
    }

    /** O(1) 定位候选组，仅提取所需数量；不足时返回空列表，由调用方拒绝该组合。 */
    public List<String> select(Map<String, Object> venue, String type, int count) {
        var candidates = grouped.getOrDefault(new Key(venue.get("approval_group_id"), venue.get("campus"), type), List.of());
        if (candidates.size() < count) return List.of();
        return candidates.subList(0, count).stream().map(r -> r.get("id").toString()).toList();
    }
}
