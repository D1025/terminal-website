package com.fonline.newdawn.update;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UpdateModels {
    private UpdateModels() {}

    public record CreateUpdateReleaseRequest(
            @NotBlank @Pattern(regexp = "[0-9A-Za-z][0-9A-Za-z.+_-]{0,79}") String version,
            @NotBlank @Pattern(regexp = "STABLE|TEST") String channel,
            @Size(max = 200_000) String releaseNotesMarkdown,
            @Size(max = 80) String minimumLauncherVersion,
            @Size(max = 253) String gameServerHost,
            @Min(1) @Max(65_535) Integer gameServerPort
    ) {}

    public record CreateUpdateFileRequest(
            @NotBlank @Size(max = 500) String targetPath,
            @NotBlank @Size(max = 160) String contentType,
            @Min(1) @Max(5_368_709_120L) long sizeBytes,
            @NotBlank @Pattern(regexp = "REPLACE|PRESERVE") String overwritePolicy
    ) {}

    public record DeleteUpdatePathRequest(
            @NotBlank @Size(max = 500) String targetPath
    ) {}

    public record EditUpdateFileRequest(
            @NotBlank @Size(max = 500) String targetPath,
            @Pattern(regexp = "REPLACE|PRESERVE") String overwritePolicy
    ) {}

    public record UpdateReleaseView(
            UUID id,
            String version,
            String channel,
            String status,
            UUID baseReleaseId,
            String baseVersion,
            String releaseNotesMarkdown,
            String minimumLauncherVersion,
            String gameServerHost,
            Integer gameServerPort,
            Instant createdAt,
            Instant publishedAt,
            long fileCount,
            long changedCount,
            long pendingCount
    ) {}

    public record UpdateFileView(
            UUID id,
            String path,
            String action,
            String overwritePolicy,
            String uploadStatus,
            long sizeBytes,
            String sha256,
            Integer legacyCrc32,
            boolean inherited
    ) {}

    public record UpdateReleaseDetail(UpdateReleaseView release, List<UpdateFileView> files) {}

    public record UpdateUploadTicket(
            UpdateFileView file,
            URI uploadUrl,
            Object headers,
            Instant expiresAt
    ) {}

    public record UpdateManifest(
            int schemaVersion,
            UUID releaseId,
            String version,
            String channel,
            String minimumLauncherVersion,
            String gameServerHost,
            Integer gameServerPort,
            Instant publishedAt,
            String manifestSha256,
            List<ManifestFile> files
    ) {}

    public record ManifestFile(
            UUID id,
            String path,
            String action,
            String overwritePolicy,
            long sizeBytes,
            String sha256,
            Integer legacyCrc32,
            String downloadUrl
    ) {}
}
