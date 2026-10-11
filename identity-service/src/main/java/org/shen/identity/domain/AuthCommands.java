package org.shen.identity.domain;

import java.util.Set;
import jakarta.validation.constraints.*;

public final class AuthCommands {
    private AuthCommands() {}
    public record Login(@NotBlank @Size(max=64) String username,
            @NotBlank @Size(max=72) String password,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String deviceId,
            @NotBlank @Pattern(regexp="WEB|IOS|ANDROID") String deviceType) {}
    public record Refresh(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String refreshToken,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String deviceId) {}
    public record CreateUser(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{3,64}") String username,
            @NotBlank @Size(min=12,max=72) String initialPassword,
            @NotBlank @Size(max=100) String displayName,
            @NotEmpty @Size(max=3) Set<@Pattern(regexp="STUDENT|RESOURCE_ADMIN|SYSTEM_ADMIN") String> roleCodes) {}
    public record Status(@NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status,
            @NotNull @Min(0) Long expectedVersion) {}
    public record Roles(@NotEmpty @Size(max=3) Set<@Pattern(regexp="STUDENT|RESOURCE_ADMIN|SYSTEM_ADMIN") String> roleCodes,
            @NotNull @Min(0) Long expectedVersion) {}
}
