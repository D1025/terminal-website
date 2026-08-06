package com.fonline.newdawn.backup;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WikiBackupModels {
    private WikiBackupModels() {}

    public static final String FORMAT = "new-dawn-wiki-backup";
    public static final int VERSION = 1;

    public record BackupManifest(
            String format,
            int version,
            Instant exportedAt,
            List<EditorBackup> editors,
            List<CategoryBackup> categories,
            List<PageBackup> pages,
            List<RevisionBackup> revisions,
            List<RelationBackup> relations,
            List<AssetBackup> assets,
            List<RevisionAssetBackup> revisionAssets
    ) {}

    public record EditorBackup(
            UUID id,
            String username,
            String passwordHash,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CategoryBackup(
            UUID id,
            UUID parentId,
            String slug,
            String name,
            String description,
            int sortOrder,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record PageBackup(
            UUID id,
            String slug,
            UUID categoryId,
            String locale,
            String status,
            UUID publishedRevisionId,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            int lockVersion
    ) {}

    public record RevisionBackup(
            UUID id,
            UUID pageId,
            int revisionNumber,
            String title,
            String summary,
            String contentMarkdown,
            JsonNode properties,
            String changeNote,
            UUID createdBy,
            Instant createdAt
    ) {}

    public record RelationBackup(
            UUID id,
            UUID revisionId,
            UUID targetPageId,
            String relationType,
            String label,
            int sortOrder,
            JsonNode metadata
    ) {}

    public record AssetBackup(
            UUID id,
            String kind,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            String altText,
            UUID uploadedBy,
            Instant createdAt,
            String zipPath
    ) {}

    public record RevisionAssetBackup(
            UUID revisionId,
            UUID assetId,
            String usage,
            String caption,
            int sortOrder
    ) {}

    public record BackupStatus(
            int editors,
            int categories,
            int articles,
            int revisions,
            int assets,
            boolean importAllowed
    ) {}

    public record BackupImportResult(
            int editors,
            int categories,
            int articles,
            int revisions,
            int relations,
            int assets
    ) {}

    public record BackupArchive(Path path, String fileName, long sizeBytes) {}
}
