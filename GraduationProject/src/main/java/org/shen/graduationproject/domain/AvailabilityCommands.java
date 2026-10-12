package org.shen.graduationproject.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 可用性接口只接收明确的时间、规则和数据模式，不接受客户端指定操作人。
 */
public final class AvailabilityCommands {
    private AvailabilityCommands() {
    }

    /**
     * 每周开放规则：weekday 为周一 1 至周日 7，本地时刻按上海时区解释，有效日期包含两端。
     */
    public record OpeningRule(@NotNull @Min(1) @Max(7) Integer weekday,
                              @NotNull LocalTime startLocal, @NotNull LocalTime endLocal,
                              @NotNull LocalDate validFrom, @NotNull LocalDate validTo,
                              @NotNull Boolean enabled) {
    }

    /**
     * 提交整套规则及资源预期版本；空 rules 列表允许明确清空开放规则。
     */
    public record OpeningRules(@NotNull @Min(0) Integer expectedVersion,
                               @NotNull @Size(max = 100) List<@NotNull @Valid OpeningRule> rules) {
    }

    /**
     * 策略字段均需明确填写。TTL、提前量、最大时长和覆盖新鲜度单位为分钟，最远预约范围单位为天。
     * 暂占、候补和用户配额字段为后续业务预留，当前可用性查询只使用相应时间与覆盖参数。
     */
    public record PolicySettings(
            @NotNull @Min(1) @Max(10080) Integer pendingApprovalTtlMinutes,
            @NotNull @Min(1) @Max(1440) Integer waitlistOfferTtlMinutes,
            @NotNull @Min(0) @Max(43200) Integer bookingLeadMinutes,
            @NotNull @Min(0) @Max(43200) Integer cancelLeadMinutes,
            @NotNull @Min(1) @Max(365) Integer maxBookingDaysAhead,
            @NotNull @Min(1) @Max(1440) Integer maxDurationMinutes,
            @NotNull @Min(1) @Max(100) Integer maxActiveApplicationsPerUser,
            @NotNull @Min(1) @Max(10080) Integer coverageFreshnessMinutes) {
    }

    /**
     * 新策略版本必须携带所基于的当前版本与制度依据，不允许仅凭前端默认数值启用。
     */
    public record PolicyVersion(@NotNull @Min(0) Integer expectedVersion,
                                @NotNull @Valid PolicySettings settings,
                                @NotBlank @Size(max = 500) String basis) {
    }

    /**
     * 来源占用登记；起止时间带偏移，sourceKey 在同资源、来源与数据模式下标识同一来源记录。
     */
    public record Occupancy(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String sourceSystem,
                            @NotBlank @Size(max = 128) String sourceKey,
                            @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                            @NotBlank @Pattern(regexp = "COURSE|EXAM|MAINTENANCE|OTHER") String reasonCode) {
    }

    /**
     * 占用局部维护契约；active=false 表示停用历史记录，expectedVersion 防止并发覆盖。
     */
    public record OccupancyUpdate(@NotNull @Min(0) Integer expectedVersion,
                                  @NotNull Boolean active,
                                  @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                                  @NotBlank @Pattern(regexp = "COURSE|EXAM|MAINTENANCE|OTHER") String reasonCode) {
    }

    /**
     * 一种设备类型所需的单件设备数量；查询还会校验类型不重复及总件数上限。
     */
    public record EquipmentRequirement(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String type,
                                       @NotNull @Min(1) @Max(20) Integer count) {
    }

    /**
     * 完整的可用性查询条件；人数、起止时间、数据模式与分页必须显式提交。
     * 必需设施按集合表达，设备按类型与数量表达；不接收客户端自报的用户身份。
     */
    public record Search(@Size(max = 100) String campus, @Size(max = 100) String preferredBuilding,
                         @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                         @NotNull @Min(1) @Max(100000) Integer participantCount,
                         @Size(max = 7) Set<@NotNull @Pattern(regexp = "PROJECTOR|WHITEBOARD|SOUND_SYSTEM|MICROPHONE|DESK|NETWORK|COMPUTER") String> requiredFeatures,
                         @Size(max = 10) List<@NotNull @Valid EquipmentRequirement> equipmentRequirements,
                         @NotBlank @Pattern(regexp = "REAL|DEMO") String dataMode,
                         @NotNull @Min(1) @Max(100000) Integer page,
                         @NotNull @Min(1) @Max(100) Integer size) {
    }
}
