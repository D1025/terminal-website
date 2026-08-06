package com.fonline.newdawn.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WikiModels {
    private WikiModels() {}

    public record PageSummary(
            UUID id, String slug, UUID categoryId, String category,
            String title, String summary, String excerpt, String status, int lockVersion, Instant updatedAt
    ) {}

    public record PageDetail(
            UUID id, String slug, UUID categoryId, String category, String locale,
            String status, int lockVersion, UUID revisionId, int revisionNumber, String title, String summary,
            String contentMarkdown, JsonNode properties, List<RelationView> relations, List<AssetView> assets,
            Instant createdAt, Instant updatedAt
    ) {}

    public record RelationView(UUID id, String relationType, String label, int sortOrder,
                               JsonNode metadata, UUID targetPageId, String targetSlug, String targetTitle) {}

    public record AssetView(UUID id, String kind, String fileName, String contentType, long sizeBytes,
                            String sha256, String altText, String usage, String caption, int sortOrder, String url) {}

    public record CategoryView(UUID id, UUID parentId, String slug, String name, String description, int sortOrder) {}

    public record RevisionView(UUID id, int revisionNumber, String title, String changeNote,
                               UUID createdBy, String createdByUsername, Instant createdAt, boolean published) {}

    public record PageWriteRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 220) String slug,
            UUID categoryId,
            @NotBlank @Pattern(regexp = "[a-z]{2}(?:-[A-Z]{2})?") @Size(max = 12) String locale,
            @NotBlank @Size(max = 220) String title,
            @Size(max = 4000) String summary,
            @NotNull @Size(max = 1_000_000) String contentMarkdown,
            @NotNull JsonNode properties,
            @Size(max = 500) String changeNote,
            @Valid @Size(max = 500) List<RelationInput> relations,
            @Valid @Size(max = 500) List<AssetInput> assets,
            @Min(0) Integer expectedLockVersion
    ) {}

    public record RelationInput(
            @NotNull UUID targetPageId,
            @NotBlank @Pattern(regexp = "[A-Z0-9_:-]{2,80}") String relationType,
            @Size(max = 180) String label,
            @Min(0) @Max(10000) int sortOrder,
            JsonNode metadata
    ) {}

    public record AssetInput(
            @NotNull UUID assetId,
            @NotBlank @Pattern(regexp = "INLINE|HERO|GALLERY|ATTACHMENT") String usage,
            @Size(max = 500) String caption,
            @Min(0) @Max(10000) int sortOrder
    ) {}

    public record CategoryRequest(
            UUID parentId,
            @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 120) String slug,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @Min(0) @Max(10000) int sortOrder
    ) {}

    public record AssetUploadRequest(
            @NotBlank @Pattern(regexp = "WIKI_IMAGE|WIKI_FILE") String kind,
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 160) String contentType,
            @Min(1) @Max(104_857_600) long sizeBytes,
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256,
            @Size(max = 500) String altText
    ) {}

    public record AssetUploadTicket(UUID assetId, URI uploadUrl, Object headers, Instant expiresAt) {}
}
