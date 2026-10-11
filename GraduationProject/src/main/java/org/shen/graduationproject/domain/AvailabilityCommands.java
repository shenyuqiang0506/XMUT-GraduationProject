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

    public record OpeningRule(@NotNull @Min(1) @Max(7) Integer weekday,
                              @NotNull LocalTime startLocal, @NotNull LocalTime endLocal,
                              @NotNull LocalDate validFrom, @NotNull LocalDate validTo,
                              @NotNull Boolean enabled) {
    }

    public record OpeningRules(@NotNull @Min(0) Integer expectedVersion,
                               @NotNull @Size(max = 100) List<@NotNull @Valid OpeningRule> rules) {
    }

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

    public record PolicyVersion(@NotNull @Min(0) Integer expectedVersion,
                                @NotNull @Valid PolicySettings settings,
                                @NotBlank @Size(max = 500) String basis) {
    }

    public record Occupancy(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String sourceSystem,
                            @NotBlank @Size(max = 128) String sourceKey,
                            @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                            @NotBlank @Pattern(regexp = "COURSE|EXAM|MAINTENANCE|OTHER") String reasonCode) {
    }

    public record OccupancyUpdate(@NotNull @Min(0) Integer expectedVersion,
                                  @NotNull Boolean active,
                                  @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                                  @NotBlank @Pattern(regexp = "COURSE|EXAM|MAINTENANCE|OTHER") String reasonCode) {
    }

    public record EquipmentRequirement(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String type,
                                       @NotNull @Min(1) @Max(20) Integer count) {
    }

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
