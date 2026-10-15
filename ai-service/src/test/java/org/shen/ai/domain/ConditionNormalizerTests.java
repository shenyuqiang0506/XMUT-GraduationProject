package org.shen.ai.domain;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 校验真实中文输入证据，不能靠模型自评或结构正确就把含糊条件当作明确条件。 */
class ConditionNormalizerTests {
    private final ConditionNormalizer normalizer=new ConditionNormalizer();
    private final LocalDate today=LocalDate.of(2026,10,14);
    private final Map<String,Object> options=Map.of("campuses",List.of("本部"),"equipmentTypes",List.of("无线 话筒","电脑"));
    private ConditionNormalizer.Normalized apply(Map<String,Object> current,String text,Map<String,Object> changes,Map<String,String> evidence) {
        return normalizer.apply(current,new AiCommands.Extraction(changes,evidence),text,today,options);
    }
    @Test void vagueAfternoonNeverCreatesDefaultTime() {
        var result=apply(Map.of(),"周五下午六个人讨论两小时，需要投影",Map.of("date","周五","startLocal","14:00","participantCount",6,"durationMinutes",120,"requiredFeatures",List.of("PROJECTOR")),
            Map.of("date","周五","startLocal","下午","participantCount","六个人","durationMinutes","两小时","requiredFeatures","投影"));
        assertThat(result.missingFields()).contains("startLocal");
        assertThat(result.conditions()).containsEntry("date","2026-10-16").containsEntry("participantCount",6);
        assertThat(result.conditions()).doesNotContainKeys("startAt","endAt");
    }
    @Test void clearTimeAndDurationUseShanghaiRegardlessOfMachineZone() {
        var result=apply(Map.of(),"明天下午两点半开始，六个人，用一小时三十分钟",Map.of("date","明天","startLocal","14:30","participantCount",6,"durationMinutes",90),
            Map.of("date","明天","startLocal","下午两点半","participantCount","六个人","durationMinutes","一小时三十分钟"));
        assertThat(result.missingFields()).isEmpty();
        assertThat(result.conditions()).containsEntry("startAt","2026-10-15T14:30+08:00").containsEntry("endAt","2026-10-15T16:00+08:00");
    }
    @Test void changingParticipantsKeepsDateTimeFeaturesAndEquipment() {
        var original=new LinkedHashMap<String,Object>(Map.of("date","2026-10-15","startLocal","14:00","durationMinutes",120,"participantCount",6,
            "requiredFeatures",List.of("PROJECTOR"),"equipmentRequirements",List.of(Map.of("type","无线 话筒","count",2))));
        var result=apply(original,"人数改成八",Map.of("participantCount",8),Map.of("participantCount","人数改成八"));
        assertThat(result.missingFields()).isEmpty();assertThat(result.conditions()).containsEntry("participantCount",8);
        assertThat(result.conditions().get("equipmentRequirements")).isEqualTo(original.get("equipmentRequirements"));
        assertThat(result.conditions()).containsEntry("startAt","2026-10-15T14:00+08:00");
    }
    @Test void inventedEvidenceOrResourceIdsAreRejected() {
        assertThatThrownBy(() -> apply(Map.of(),"讨论项目",Map.of("participantCount",6),Map.of("participantCount","六人"))).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> apply(Map.of(),"资源1",Map.of("resourceIds",List.of("1")),Map.of("resourceIds","资源1"))).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> apply(Map.of(),"人数改成八",Map.of("requiredFeatures",List.of()),Map.of("requiredFeatures","人数改成八"))).isInstanceOf(AiFailure.class);
    }
    @Test void equipmentCountRequiresMatchingUserEvidence() {
        var good=apply(Map.of(),"需要两个无线话筒",Map.of("equipmentRequirements",List.of(Map.of("type","无线 话筒","count",2))),Map.of("equipmentRequirements","两个无线话筒"));
        assertThat(good.conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","无线 话筒","count",2)));
        assertThatThrownBy(() -> apply(Map.of(),"需要无线话筒",Map.of("equipmentRequirements",List.of(Map.of("type","无线 话筒","count",1))),Map.of("equipmentRequirements","无线话筒"))).isInstanceOf(AiFailure.class);
    }
    @Test void unknownDirectoryValuesRemainUnresolvedAcrossTurns() {
        var initial=Map.<String,Object>of("campus","未知校区","equipmentRequirements",List.of(Map.of("type","不存在设备","count",1)));
        var result=apply(initial,"人数六人",Map.of("participantCount",6),Map.of("participantCount","六人"));
        assertThat(result.missingFields()).contains("campus","equipmentRequirements");
    }
    @Test void crossDayAndPastWeekdayAreNotSilentlyShifted() {
        var result=normalizer.validate(Map.of("date","2026-10-15","startLocal","23:00","durationMinutes",120,"participantCount",6),today,options);
        assertThat(result.missingFields()).contains("timeRange");
        assertThat(ConditionNormalizer.date("下周五",today)).isEqualTo(LocalDate.of(2026,10,23));
        assertThat(ConditionNormalizer.date("周一",today)).isEqualTo(LocalDate.of(2026,10,12));
        assertThat(normalizer.validate(Map.of("date","2026-10-12","startLocal","09:00","durationMinutes",60,"participantCount",6),today,options).missingFields()).contains("timeRange");
    }
    @Test void explicitlyRemovingRequirementsDoesNotNeedAnInventedDefault() {
        var result=apply(Map.of("requiredFeatures",List.of("PROJECTOR")),"不需要投影了",Map.of("requiredFeatures",List.of()),Map.of("requiredFeatures","不需要投影"));
        assertThat(result.conditions().get("requiredFeatures")).isEqualTo(List.of());
    }
    @Test void negativeAndRangeCountsAreNotChangedIntoPositiveDefaults() {
        for(String value:List.of("-1人","负一人","五到八人","6～8人","2.5人","六人左右","至少五人")) {
            var result=apply(Map.of(),value,Map.of("participantCount",8),Map.of("participantCount",value));
            assertThat(result.missingFields()).contains("participantCount");
        }
    }
    @Test void decimalClockDoesNotBecomeAnUnrelatedHour() {
        assertThatThrownBy(() -> ConditionNormalizer.time("下午2.5点")).isInstanceOf(IllegalArgumentException.class);
        var result=apply(Map.of(),"1.5小时",Map.of("durationMinutes",90),Map.of("durationMinutes","1.5小时"));
        assertThat(result.conditions()).doesNotContainKey("durationMinutes");
    }
    private Map<String,Object> operation(String name,Object items){return Map.of("operation",name,"items",items);}
    private ConditionNormalizer.Normalized featureChange(Object patch,String proof) {
        return apply(Map.of("requiredFeatures",List.of("PROJECTOR","WHITEBOARD")),proof,Map.of("requiredFeatures",patch),Map.of("requiredFeatures",proof));
    }
    @Test void featureAdditionsKeepPreviouslyConfirmedRequirementsForBothFormats() {
        for(Object patch:List.of(List.of("WHITEBOARD"),operation("ADD",List.of("WHITEBOARD")))) {
            var result=apply(Map.of("requiredFeatures",List.of("PROJECTOR")),"再加上白板",Map.of("requiredFeatures",patch),Map.of("requiredFeatures","再加上白板"));
            assertThat(result.conditions().get("requiredFeatures")).isEqualTo(List.of("PROJECTOR","WHITEBOARD"));
        }
    }
    @Test void localFeatureRemovalKeepsOtherRequirementsAndExplicitClearRemovesAll() {
        for(Object patch:List.of(List.of(),operation("REMOVE",List.of("WHITEBOARD"))))
            assertThat(featureChange(patch,"取消白板").conditions().get("requiredFeatures")).isEqualTo(List.of("PROJECTOR"));
        assertThat(featureChange(operation("REPLACE",List.of("WHITEBOARD")),"只要白板").conditions().get("requiredFeatures")).isEqualTo(List.of("WHITEBOARD"));
        assertThat(featureChange(operation("CLEAR",List.of()),"取消所有设施要求").conditions().get("requiredFeatures")).isEqualTo(List.of());
    }
    @Test void operationNamesCannotAuthorizeUnstatedReplacementsOrGlobalClears() {
        assertThatThrownBy(() -> featureChange(operation("REPLACE",List.of("WHITEBOARD")),"再加上白板")).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> featureChange(operation("CLEAR",List.of()),"取消白板")).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> featureChange(operation("MERGE",List.of("WHITEBOARD")),"取消白板")).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> featureChange(operation("ADD",List.of("WHITEBOARD")),"再加上投影")).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> featureChange(operation("DELETE",List.of("WHITEBOARD")),"取消白板")).isInstanceOf(AiFailure.class);
    }
    @Test void negatedAdditionOrMixedActionsCannotAuthorizeAnArrayMutation() {
        for(String proof:List.of("不用再加网络","不要再加网络","不需要追加网络","取消白板再加网络")) {
            for(Object patch:List.of(List.of("NETWORK"),operation("ADD",List.of("NETWORK"))))
                assertThatThrownBy(() -> featureChange(patch,proof)).isInstanceOf(AiFailure.class);
        }
        assertThatThrownBy(() -> equipmentChange(List.of(Map.of("type","无线 话筒","count",2)),
            operation("ADD",List.of(Map.of("type","电脑","count",2))),"不用再加两台电脑")).isInstanceOf(AiFailure.class);
    }
    private ConditionNormalizer.Normalized equipmentChange(List<Map<String,Object>> current,Object patch,String proof) {
        return apply(Map.of("equipmentRequirements",current),proof,Map.of("equipmentRequirements",patch),Map.of("equipmentRequirements",proof));
    }
    @Test void addingAnotherEquipmentTypePreservesEarlierTypes() {
        for(Object patch:List.of(List.of(Map.of("type","电脑","count",2)),operation("ADD",List.of(Map.of("type","电脑","count",2))))) {
            var result=equipmentChange(List.of(Map.of("type","无线 话筒","count",2)),patch,"再加两台电脑");
            assertThat(result.conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","无线 话筒","count",2),Map.of("type","电脑","count",2)));
        }
    }
    @Test void equipmentAdditionAndSettingQuantityHaveDifferentEffects() {
        var current=List.<Map<String,Object>>of(Map.of("type","电脑","count",2),Map.of("type","无线 话筒","count",2));
        var extra=equipmentChange(current,operation("ADD",List.of(Map.of("type","电脑","count",2))),"再加两台电脑");
        var set=equipmentChange(current,operation("MERGE",List.of(Map.of("type","电脑","count",3))),"电脑要三台");
        assertThat(extra.conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","无线 话筒","count",2),Map.of("type","电脑","count",4)));
        assertThat(set.conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","无线 话筒","count",2),Map.of("type","电脑","count",3)));
    }
    @Test void equipmentRemovalReplacementAndClearRequireTheCorrespondingEvidence() {
        var current=List.<Map<String,Object>>of(Map.of("type","电脑","count",2),Map.of("type","无线 话筒","count",2));
        for(Object patch:List.of(List.of(),operation("REMOVE",List.of("电脑"))))
            assertThat(equipmentChange(current,patch,"取消电脑").conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","无线 话筒","count",2)));
        assertThat(equipmentChange(current,operation("REPLACE",List.of(Map.of("type","电脑","count",1))),"只要一台电脑").conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","电脑","count",1)));
        assertThat(equipmentChange(current,operation("CLEAR",List.of()),"取消所有设备要求").conditions().get("equipmentRequirements")).isEqualTo(List.of());
        assertThatThrownBy(() -> equipmentChange(current,operation("CLEAR",List.of()),"取消电脑")).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> equipmentChange(current,operation("REMOVE",List.of("无线 话筒")),"取消电脑")).isInstanceOf(AiFailure.class);
    }
    @Test void mergedEquipmentLimitsContinueToBlockResourceSearch() {
        var result=equipmentChange(List.of(Map.of("type","电脑","count",20)),operation("ADD",List.of(Map.of("type","电脑","count",2))),"再加两台电脑");
        assertThat(result.missingFields()).contains("equipmentRequirements");
        assertThat(result.conditions().get("equipmentRequirements")).isEqualTo(List.of(Map.of("type","电脑","count",22)));
    }
    @Test void digitalAndNamedTimesUseTheSamePeriodConversion() {
        for(String proof:List.of("下午3:00","下午三点","晚上3:00","晚上三点"))assertThat(ConditionNormalizer.time(proof)).isEqualTo(LocalTime.of(15,0));
        assertThat(ConditionNormalizer.time("下午15:30")).isEqualTo(LocalTime.of(15,30));
        assertThat(ConditionNormalizer.time("上午3:00")).isEqualTo(LocalTime.of(3,0));
        assertThat(ConditionNormalizer.time("凌晨12:00")).isEqualTo(LocalTime.MIDNIGHT);
        assertThatThrownBy(() -> ConditionNormalizer.time("上午15:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionNormalizer.time("上午下午3:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionNormalizer.time("14:00到16:00")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void compoundHalfHoursProduceTheCompleteBookingWindow() {
        var expected=Map.of("半小时",30,"一个半小时",90,"两个半小时",150,"两小时半",150,"两小时三十分钟",150);
        for(var entry:expected.entrySet()) {
            var result=apply(Map.of("date","2026-10-15","startLocal","14:00","participantCount",6),entry.getKey(),Map.of("durationMinutes",entry.getValue()),Map.of("durationMinutes",entry.getKey()));
            assertThat(result.missingFields()).isEmpty();assertThat(result.conditions()).containsEntry("durationMinutes",entry.getValue());
            assertThat(OffsetDateTime.parse(result.conditions().get("endAt").toString())).isEqualTo(OffsetDateTime.parse("2026-10-15T14:00+08:00").plusMinutes(entry.getValue()));
        }
    }
}
