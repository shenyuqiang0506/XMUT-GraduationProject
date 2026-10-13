package org.shen.graduationproject;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.domain.EquipmentIndex;
import static org.assertj.core.api.Assertions.*;

/** 确认索引优化仍隔离审批组、校区、类型，并按原候选顺序选择设备。 */
class EquipmentIndexTests {
    private Map<String, Object> row(long id, long group, String campus, String type) {
        return Map.of("id", id, "approval_group_id", group, "campus", campus, "equipment_type", type);
    }

    @Test void selectionMatchesOriginalScanAcrossGroupsCampusesAndTypes() {
        var all = List.of(row(1, 1, "本部", "CAMERA"), row(2, 2, "本部", "CAMERA"),
                row(3, 1, "分校", "CAMERA"), row(4, 1, "本部", "MIC"), row(5, 1, "本部", "CAMERA"));
        var index = new EquipmentIndex(all);
        assertThat(index.select(all.getFirst(), "CAMERA", 2)).containsExactly("1", "5");
        assertThat(index.select(all.getFirst(), "CAMERA", 3)).isEmpty();
        assertThat(index.select(all.get(1), "CAMERA", 1)).containsExactly("2");
        assertThat(index.select(all.get(2), "CAMERA", 1)).containsExactly("3");
        assertThat(index.select(all.getFirst(), "MIC", 1)).containsExactly("4");
        assertThat(index.select(all.getFirst(), "UNKNOWN", 1)).isEmpty();
    }

    @Test void nullCampusRemainsASeparateGroupAsBefore() {
        var unspecified = new HashMap<>(row(1, 1, "本部", "CAMERA"));
        unspecified.put("campus", null);
        var index = new EquipmentIndex(List.of(unspecified, row(2, 1, "本部", "CAMERA")));
        assertThat(index.select(unspecified, "CAMERA", 1)).containsExactly("1");
    }
}
