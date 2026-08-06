package com.fonline.newdawn.release;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public final class ReleaseModels {
    private ReleaseModels() {}

    public record ReleaseView(
            UUID id, String version, String platform, String channel, String status, String fileName,
            String contentType, long sizeBytes, String sha256, String releaseNotesMarkdown,
            String minimumLauncherVersion, Instant createdAt, Instant publishedAt, String downloadUrl
    ) {}

    public record CreateReleaseRequest(
            @NotBlank @Pattern(regexp = "[0-9A-Za-z][0-9A-Za-z.+_-]{0,79}") String version,
            @NotBlank @Pattern(regexp = "WINDOWS|LINUX|MACOS") String platform,
            @NotBlank @Pattern(regexp = "STABLE|TEST") String channel,
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 160) String contentType,
            @Min(1) @Max(5_368_709_120L) long sizeBytes,
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256,
            @Size(max = 200_000) String releaseNotesMarkdown,
            @Size(max = 80) String minimumLauncherVersion
    ) {}

    public record ReleaseUploadTicket(ReleaseView release, URI uploadUrl, Object headers, Instant expiresAt) {}
}
