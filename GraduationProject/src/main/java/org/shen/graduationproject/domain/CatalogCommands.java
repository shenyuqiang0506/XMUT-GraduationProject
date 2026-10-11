package org.shen.graduationproject.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.*;

public final class CatalogCommands {
    private CatalogCommands() {
    }

    public record Resource(
            @NotBlank @Pattern(regexp = "VENUE|EQUIPMENT") String resourceType,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String sourceSystem,
            @NotBlank @Size(max = 64) String sourceId, @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String campus, @Size(max = 100) String building, @Size(max = 16) String floorNo,
            @Size(max = 100) String category, @Size(max = 100) String subcategory,
            @Min(0) Integer capacity, @Min(0) Integer examCapacity,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal area,
            @NotBlank @Pattern(regexp = "YES|NO|UNKNOWN") String sourceBorrowable,
            @NotBlank @Pattern(regexp = "DRAFT|OPEN|CLOSED|RETIRED") String platformStatus,
            @Pattern(regexp = "[1-9][0-9]{0,18}") String approvalGroupId,
            @NotBlank @Pattern(regexp = "REAL|DEMO") String dataMode, @Size(max = 4000) String remark,
            @Size(max = 20) Map<@Pattern(regexp = "PROJECTOR|WHITEBOARD|SOUND_SYSTEM|MICROPHONE|DESK|NETWORK|COMPUTER") String, @NotBlank @Size(max = 200) String> features,
            @Size(max = 64) String assetCode, @Size(max = 32) String equipmentType,
            @Pattern(regexp = "GOOD|DAMAGED|MAINTENANCE") String conditionStatus) {
    }

    public record Group(@NotBlank @Size(max = 100) String name) {
    }

    public record Members(@NotNull @Size(max = 100) Set<@Pattern(regexp = "[1-9][0-9]{0,18}") String> userIds) {
    }
}
