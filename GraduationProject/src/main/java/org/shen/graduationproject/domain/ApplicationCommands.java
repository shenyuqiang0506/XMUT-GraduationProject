package org.shen.graduationproject.domain;

import java.time.OffsetDateTime;
import java.util.*;

import jakarta.validation.constraints.*;

/**
 * 联合预约输入契约：申请人和审批组由服务端确定，不接受客户端代填。
 */
public final class ApplicationCommands {
    private ApplicationCommands() {
    }

    public record Create(@NotBlank @Size(max = 200) String activityName, @Size(max = 2000) String description,
                         @NotNull @Min(1) @Max(100000) Integer participantCount,
                         @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                         @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String venueId,
                         @Size(max = 40) List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> equipmentIds,
                         @Size(max = 7) Set<@NotNull @Pattern(regexp = "PROJECTOR|WHITEBOARD|SOUND_SYSTEM|MICROPHONE|DESK|NETWORK|COMPUTER") String> requiredFeatures) {
    }

    public record Action(@NotNull @Min(0) Integer expectedVersion, @Size(max = 500) String reason) {
    }

    public record Resolve(@NotNull @Min(0) Integer expectedVersion,
                          @NotBlank @Pattern(regexp = "CANCEL|REVALIDATE") String action,
                          @NotBlank @Size(max = 500) String reason) {
    }
}
