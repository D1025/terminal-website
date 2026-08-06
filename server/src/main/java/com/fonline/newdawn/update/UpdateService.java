package com.fonline.newdawn.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonline.newdawn.audit.AuditRepository;
import com.fonline.newdawn.common.ApiException;
import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.security.AuthenticatedUser;
import com.fonline.newdawn.storage.StorageService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.fonline.newdawn.update.UpdateModels.*;

@Service
public class UpdateService {
    private static final int MAX_FILES_PER_RELEASE = 20_000;

    private final JdbcClient jdbc;
    private final StorageService storage;
    private final AppProperties properties;
    private final AuditRepository audit;
    private final ObjectMapper objectMapper;

    public UpdateService(JdbcClient jdbc, StorageService storage, AppProperties properties,
                         AuditRepository audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<UpdateReleaseView> adminReleases() {
        return jdbc.sql(summarySql(""))
                .query((rs, row) -> mapRelease(rs)).list();
    }

    public UpdateReleaseDetail detail(UUID id) {
        return detail(id, false, "");
    }

    public UpdateReleaseDetail detail(UUID id, boolean includeInherited, String query) {
        UpdateReleaseView release = findRelease(id);
        List<UpdateFileView> files = jdbc.sql("""
                SELECT * FROM update_file
                WHERE release_id = :releaseId
                  AND (:includeInherited OR inherited = FALSE)
                  AND (:queryEmpty OR target_path ILIKE ('%' || :query || '%'))
                ORDER BY path_key
                LIMIT 1000
                """).param("releaseId", id).param("includeInherited", includeInherited)
                .param("query", query == null ? "" : query.trim())
                .param("queryEmpty", query == null || query.isBlank())
                .query((rs, row) -> mapFile(rs)).list();
        return new UpdateReleaseDetail(release, files);
    }

    @Transactional
    public UpdateReleaseDetail create(CreateUpdateReleaseRequest request, AuthenticatedUser actor) {
        validateGameServer(request.gameServerHost(), request.gameServerPort());
        UUID id = UUID.randomUUID();
        UUID baseReleaseId = jdbc.sql("""
                SELECT id FROM update_release WHERE channel = :channel AND status = 'PUBLISHED'
                """).param("channel", request.channel()).query(UUID.class).optional().orElse(null);

        try {
            jdbc.sql("""
                    INSERT INTO update_release(id, version, channel, status, base_release_id,
                                               release_notes_markdown, minimum_launcher_version,
                                               game_server_host, game_server_port, created_by)
                    VALUES (:id, :version, :channel, 'DRAFT', :baseReleaseId,
                            :notes, :minimumLauncher, :gameHost, :gamePort, :actorId)
                    """)
                    .param("id", id)
                    .param("version", request.version())
                    .param("channel", request.channel())
                    .param("baseReleaseId", baseReleaseId)
                    .param("notes", blankToNull(request.releaseNotesMarkdown()))
                    .param("minimumLauncher", blankToNull(request.minimumLauncherVersion()))
                    .param("gameHost", blankToNull(request.gameServerHost()))
                    .param("gamePort", request.gameServerPort())
                    .param("actorId", actor.id())
                    .update();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_RELEASE_EXISTS",
                    "This update version already exists in the selected channel.");
        }

        if (baseReleaseId != null) {
            jdbc.sql("""
                    INSERT INTO update_file(id, release_id, target_path, path_key, action, overwrite_policy,
                                            upload_status, object_key, file_name, content_type, size_bytes,
                                            sha256, legacy_crc32, inherited)
                    SELECT gen_random_uuid(), :newReleaseId, target_path, path_key, action, overwrite_policy,
                           upload_status, object_key, file_name, content_type, size_bytes,
                           sha256, legacy_crc32, TRUE
                    FROM update_file WHERE release_id = :baseReleaseId
                    """).param("newReleaseId", id).param("baseReleaseId", baseReleaseId).update();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", request.version());
        details.put("channel", request.channel());
        if (baseReleaseId != null) details.put("baseReleaseId", baseReleaseId);
        audit.record(actor.id(), "UPDATE_RELEASE_CREATED", "UPDATE_RELEASE", id, details);
        return detail(id);
    }

    @Transactional
    public UpdateUploadTicket createFileUpload(UUID releaseId, CreateUpdateFileRequest request,
                                               AuthenticatedUser actor) {
        EditableRelease release = editableRelease(releaseId);
        UpdatePathPolicy.NormalizedPath path = UpdatePathPolicy.normalize(request.targetPath());
        ExistingFile existing = findExistingFile(releaseId, path.key());
        if (existing == null && fileCount(releaseId) >= MAX_FILES_PER_RELEASE) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_FILE_LIMIT",
                    "An update release cannot contain more than " + MAX_FILES_PER_RELEASE + " manifest entries.");
        }

        UUID fileId = UUID.randomUUID();
        String objectKey = "updates/" + release.channel().toLowerCase(Locale.ROOT) + "/" + releaseId + "/"
                + fileId + "/" + storage.safeFileName(path.fileName());

        if (existing != null) {
            jdbc.sql("DELETE FROM update_file WHERE id = :id").param("id", existing.id()).update();
        }
        jdbc.sql("""
                INSERT INTO update_file(id, release_id, target_path, path_key, action, overwrite_policy,
                                        upload_status, object_key, file_name, content_type, size_bytes, inherited)
                VALUES (:id, :releaseId, :targetPath, :pathKey, 'UPSERT', :overwritePolicy,
                        'PENDING', :objectKey, :fileName, :contentType, :sizeBytes, FALSE)
                """)
                .param("id", fileId)
                .param("releaseId", releaseId)
                .param("targetPath", path.value())
                .param("pathKey", path.key())
                .param("overwritePolicy", request.overwritePolicy())
                .param("objectKey", objectKey)
                .param("fileName", path.fileName())
                .param("contentType", request.contentType().trim())
                .param("sizeBytes", request.sizeBytes())
                .update();
        jdbc.sql("UPDATE update_release SET status = 'UPLOADING' WHERE id = :id").param("id", releaseId).update();

        StorageService.UploadTicket ticket = storage.presignUpload(objectKey, request.contentType().trim(), request.sizeBytes());
        audit.record(actor.id(), "UPDATE_FILE_UPLOAD_CREATED", "UPDATE_RELEASE", releaseId,
                Map.of("fileId", fileId, "path", path.value(), "sizeBytes", request.sizeBytes()));
        return new UpdateUploadTicket(findFile(releaseId, fileId), ticket.url(), ticket.headers(),
                Instant.now().plus(properties.storage().uploadUrlTtl()));
    }

    @Transactional
    public UpdateFileView completeFile(UUID releaseId, UUID fileId, AuthenticatedUser actor) {
        editableRelease(releaseId);
        PendingFile pending = jdbc.sql("""
                SELECT object_key, size_bytes, upload_status FROM update_file
                WHERE id = :fileId AND release_id = :releaseId AND action = 'UPSERT'
                """).param("fileId", fileId).param("releaseId", releaseId)
                .query((rs, row) -> new PendingFile(rs.getString("object_key"), rs.getLong("size_bytes"),
                        rs.getString("upload_status")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                        "The update file was not found."));
        if ("READY".equals(pending.status())) return findFile(releaseId, fileId);

        StorageService.StoredObject object = storage.head(pending.objectKey());
        if (object.sizeBytes() != pending.sizeBytes()) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_FILE_SIZE_MISMATCH",
                    "The uploaded file size does not match the declared size.");
        }
        StorageService.ObjectDigests digests = storage.calculateDigests(pending.objectKey());
        jdbc.sql("""
                UPDATE update_file SET upload_status = 'READY', sha256 = :sha256, legacy_crc32 = :legacyCrc32
                WHERE id = :fileId AND release_id = :releaseId AND upload_status = 'PENDING'
                """).param("sha256", digests.sha256()).param("legacyCrc32", digests.legacyCrc32())
                .param("fileId", fileId).param("releaseId", releaseId).update();
        refreshDraftStatus(releaseId);
        audit.record(actor.id(), "UPDATE_FILE_UPLOADED", "UPDATE_RELEASE", releaseId,
                Map.of("fileId", fileId, "sha256", digests.sha256()));
        return findFile(releaseId, fileId);
    }

    @Transactional
    public UpdateReleaseDetail markDeleted(UUID releaseId, DeleteUpdatePathRequest request, AuthenticatedUser actor) {
        editableRelease(releaseId);
        UpdatePathPolicy.NormalizedPath path = UpdatePathPolicy.normalize(request.targetPath());
        ExistingFile existing = findExistingFile(releaseId, path.key());
        if (existing == null && fileCount(releaseId) >= MAX_FILES_PER_RELEASE) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_FILE_LIMIT",
                    "An update release cannot contain more than " + MAX_FILES_PER_RELEASE + " manifest entries.");
        }
        if (existing != null) jdbc.sql("DELETE FROM update_file WHERE id = :id").param("id", existing.id()).update();

        jdbc.sql("""
                INSERT INTO update_file(id, release_id, target_path, path_key, action, overwrite_policy,
                                        upload_status, size_bytes, inherited)
                VALUES (:id, :releaseId, :targetPath, :pathKey, 'DELETE', 'REPLACE', 'READY', 0, FALSE)
                """).param("id", UUID.randomUUID()).param("releaseId", releaseId)
                .param("targetPath", path.value()).param("pathKey", path.key()).update();
        refreshDraftStatus(releaseId);
        audit.record(actor.id(), "UPDATE_PATH_MARKED_FOR_DELETION", "UPDATE_RELEASE", releaseId,
                Map.of("path", path.value()));
        return detail(releaseId);
    }

    @Transactional
    public UpdateReleaseDetail editFile(UUID releaseId, UUID fileId, EditUpdateFileRequest request,
                                        AuthenticatedUser actor) {
        EditableRelease release = editableRelease(releaseId);
        EditableFile file = jdbc.sql("""
                SELECT target_path, path_key, action, overwrite_policy, inherited
                FROM update_file WHERE id = :fileId AND release_id = :releaseId
                FOR UPDATE
                """).param("fileId", fileId).param("releaseId", releaseId)
                .query((rs, row) -> new EditableFile(
                        rs.getString("target_path"), rs.getString("path_key"), rs.getString("action"),
                        rs.getString("overwrite_policy"), rs.getBoolean("inherited")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                        "The update file was not found."));
        if (file.inherited()) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_FILE_INHERITED",
                    "Inherited entries cannot be edited directly. Replace or delete the path instead.");
        }

        UpdatePathPolicy.NormalizedPath path = UpdatePathPolicy.normalize(request.targetPath());
        boolean pathChanged = !file.pathKey().equals(path.key());
        if (pathChanged && findExistingFile(releaseId, path.key()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_PATH_EXISTS",
                    "Another manifest entry already uses this target path.");
        }

        String overwritePolicy = "UPSERT".equals(file.action())
                ? Objects.requireNonNullElse(request.overwritePolicy(), file.overwritePolicy())
                : "REPLACE";
        jdbc.sql("""
                UPDATE update_file
                SET target_path = :targetPath, path_key = :pathKey, overwrite_policy = :overwritePolicy,
                    file_name = CASE WHEN action = 'UPSERT' THEN :fileName ELSE NULL END
                WHERE id = :fileId AND release_id = :releaseId
                """).param("targetPath", path.value()).param("pathKey", path.key())
                .param("overwritePolicy", overwritePolicy).param("fileName", path.fileName())
                .param("fileId", fileId).param("releaseId", releaseId).update();

        if (pathChanged && release.baseReleaseId() != null) {
            restoreBasePath(releaseId, release.baseReleaseId(), file.pathKey());
        }
        audit.record(actor.id(), "UPDATE_FILE_EDITED", "UPDATE_RELEASE", releaseId,
                Map.of("fileId", fileId, "oldPath", file.targetPath(), "path", path.value(),
                        "overwritePolicy", overwritePolicy));
        return detail(releaseId);
    }

    @Transactional
    public UpdateReleaseDetail revertChange(UUID releaseId, UUID fileId, AuthenticatedUser actor) {
        EditableRelease release = editableRelease(releaseId);
        ChangedFile file = jdbc.sql("""
                SELECT target_path, path_key, inherited FROM update_file
                WHERE id = :fileId AND release_id = :releaseId
                """).param("fileId", fileId).param("releaseId", releaseId)
                .query((rs, row) -> new ChangedFile(rs.getString("target_path"), rs.getString("path_key"),
                        rs.getBoolean("inherited")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                        "The update file was not found."));
        if (file.inherited()) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_FILE_UNCHANGED",
                    "Inherited files can be changed or deleted, but they cannot be reverted again.");
        }

        jdbc.sql("DELETE FROM update_file WHERE id = :fileId").param("fileId", fileId).update();
        if (release.baseReleaseId() != null) {
            restoreBasePath(releaseId, release.baseReleaseId(), file.pathKey());
        }
        refreshDraftStatus(releaseId);
        audit.record(actor.id(), "UPDATE_CHANGE_REVERTED", "UPDATE_RELEASE", releaseId,
                Map.of("path", file.targetPath()));
        return detail(releaseId);
    }

    @Transactional
    public UpdateReleaseDetail publish(UUID releaseId, AuthenticatedUser actor) {
        ReleaseState release = jdbc.sql("""
                SELECT id, version, channel, status, base_release_id FROM update_release
                WHERE id = :id FOR UPDATE
                """).param("id", releaseId).query((rs, row) -> new ReleaseState(
                        rs.getObject("id", UUID.class), rs.getString("version"), rs.getString("channel"),
                        rs.getString("status"), rs.getObject("base_release_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_RELEASE_NOT_FOUND",
                        "The update release was not found."));
        if ("PUBLISHED".equals(release.status())) return detail(releaseId);
        if (!List.of("DRAFT", "UPLOADING", "RETIRED").contains(release.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_RELEASE_NOT_PUBLISHABLE",
                    "This update release cannot be published.");
        }

        UUID activeReleaseId = jdbc.sql("""
                SELECT id FROM update_release WHERE channel = :channel AND status = 'PUBLISHED' FOR UPDATE
                """).param("channel", release.channel()).query(UUID.class).optional().orElse(null);

        boolean rollback = "RETIRED".equals(release.status());
        if (!rollback) {
            long pending = countFiles(releaseId, "upload_status = 'PENDING'");
            long changed = countFiles(releaseId, "inherited = FALSE");
            long downloadable = countFiles(releaseId, "action = 'UPSERT' AND upload_status = 'READY'");
            if (pending > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "UPDATE_UPLOADS_PENDING",
                        "Every update file must finish uploading before publication.");
            }
            if (changed == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "UPDATE_HAS_NO_CHANGES",
                        "The draft does not contain any changes relative to its base release.");
            }
            if (downloadable == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "UPDATE_HAS_NO_FILES",
                        "An update manifest must contain at least one downloadable file.");
            }
            if (!Objects.equals(activeReleaseId, release.baseReleaseId())) {
                throw new ApiException(HttpStatus.CONFLICT, "UPDATE_BASE_STALE",
                        "Another release was published after this draft was created. Create a new draft from the current release.");
            }
        }

        if (activeReleaseId != null && !activeReleaseId.equals(releaseId)) {
            jdbc.sql("UPDATE update_release SET status = 'RETIRED' WHERE id = :id")
                    .param("id", activeReleaseId).update();
        }
        jdbc.sql("UPDATE update_release SET status = 'PUBLISHED', published_at = now() WHERE id = :id")
                .param("id", releaseId).update();
        audit.record(actor.id(), rollback ? "UPDATE_RELEASE_ROLLED_BACK" : "UPDATE_RELEASE_PUBLISHED",
                "UPDATE_RELEASE", releaseId, Map.of("version", release.version(), "channel", release.channel()));
        return detail(releaseId);
    }

    @Transactional
    public void discard(UUID releaseId, AuthenticatedUser actor) {
        DeletableRelease release = jdbc.sql("""
                SELECT id, version, channel, status FROM update_release WHERE id = :id FOR UPDATE
                """).param("id", releaseId).query((rs, row) -> new DeletableRelease(
                        rs.getObject("id", UUID.class), rs.getString("version"),
                        rs.getString("channel"), rs.getString("status")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "UPDATE_RELEASE_NOT_FOUND", "The update release was not found."));
        boolean hasDependents = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM update_release WHERE base_release_id = :id)
                """).param("id", releaseId).query(Boolean.class).single();
        if (hasDependents) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_RELEASE_HAS_DEPENDENTS",
                    "Delete newer snapshots based on this update before deleting this version.");
        }
        String prefix = "updates/" + release.channel().toLowerCase(Locale.ROOT) + "/" + releaseId + "/";
        int deletedObjects = storage.deletePrefix(prefix);
        jdbc.sql("DELETE FROM update_release WHERE id = :id").param("id", releaseId).update();
        audit.record(actor.id(), "UPDATE_RELEASE_DELETED", "UPDATE_RELEASE", releaseId,
                Map.of("version", release.version(), "channel", release.channel(),
                        "status", release.status(), "deletedObjects", deletedObjects));
    }

    public UpdateManifest manifest(String requestedChannel) {
        String channel = normalizeChannel(requestedChannel);
        PublishedRelease release = jdbc.sql("""
                SELECT id, version, channel, minimum_launcher_version, game_server_host,
                       game_server_port, published_at
                FROM update_release WHERE channel = :channel AND status = 'PUBLISHED'
                """).param("channel", channel).query((rs, row) -> new PublishedRelease(
                        rs.getObject("id", UUID.class), rs.getString("version"), rs.getString("channel"),
                        rs.getString("minimum_launcher_version"), rs.getString("game_server_host"),
                        (Integer) rs.getObject("game_server_port"), rs.getTimestamp("published_at").toInstant()))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_NOT_FOUND",
                        "No update manifest has been published in this channel."));

        List<ManifestFile> files = jdbc.sql("""
                SELECT id, target_path, action, overwrite_policy, size_bytes, sha256, legacy_crc32
                FROM update_file WHERE release_id = :releaseId AND upload_status = 'READY' ORDER BY path_key
                """).param("releaseId", release.id()).query((rs, row) -> {
                    UUID fileId = rs.getObject("id", UUID.class);
                    String action = rs.getString("action");
                    return new ManifestFile(fileId, rs.getString("target_path"), action,
                            rs.getString("overwrite_policy"), rs.getLong("size_bytes"), rs.getString("sha256"),
                            (Integer) rs.getObject("legacy_crc32"), "UPSERT".equals(action)
                            ? "/api/v1/updates/files/" + fileId + "/download" : null);
                }).list();

        UnsignedManifest unsigned = new UnsignedManifest(1, release.id(), release.version(), release.channel(),
                release.minimumLauncherVersion(), release.gameServerHost(), release.gameServerPort(),
                release.publishedAt(), files);
        return new UpdateManifest(unsigned.schemaVersion(), unsigned.releaseId(), unsigned.version(), unsigned.channel(),
                unsigned.minimumLauncherVersion(), unsigned.gameServerHost(), unsigned.gameServerPort(),
                unsigned.publishedAt(), manifestHash(unsigned), files);
    }

    LegacySnapshot legacySnapshot(String requestedChannel) {
        String channel = normalizeChannel(requestedChannel);
        PublishedRelease release = jdbc.sql("""
                SELECT id, version, channel, minimum_launcher_version, game_server_host,
                       game_server_port, published_at
                FROM update_release WHERE channel = :channel AND status = 'PUBLISHED'
                """).param("channel", channel).query((rs, row) -> new PublishedRelease(
                        rs.getObject("id", UUID.class), rs.getString("version"), rs.getString("channel"),
                        rs.getString("minimum_launcher_version"), rs.getString("game_server_host"),
                        (Integer) rs.getObject("game_server_port"), rs.getTimestamp("published_at").toInstant()))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_NOT_FOUND",
                        "No update manifest has been published in this channel."));
        List<LegacyFile> files = jdbc.sql("""
                SELECT target_path, overwrite_policy, size_bytes, legacy_crc32, object_key
                FROM update_file
                WHERE release_id = :releaseId AND action = 'UPSERT' AND upload_status = 'READY'
                  AND size_bytes <= 2147483647
                ORDER BY path_key
                """).param("releaseId", release.id()).query((rs, row) -> new LegacyFile(
                        rs.getString("target_path"), rs.getString("overwrite_policy"), rs.getInt("size_bytes"),
                        rs.getInt("legacy_crc32"), rs.getString("object_key"))).list();
        return new LegacySnapshot(release.version(), release.gameServerHost(), release.gameServerPort(), files);
    }

    @Transactional
    public URI download(UUID fileId) {
        DownloadFile file = jdbc.sql("""
                SELECT f.object_key, f.file_name, f.size_bytes, r.id AS release_id
                FROM update_file f JOIN update_release r ON r.id = f.release_id
                WHERE f.id = :fileId AND f.action = 'UPSERT' AND f.upload_status = 'READY'
                  AND r.status = 'PUBLISHED'
                """).param("fileId", fileId).query((rs, row) -> new DownloadFile(
                        rs.getString("object_key"), rs.getString("file_name"), rs.getLong("size_bytes"),
                        rs.getObject("release_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                        "The published update file was not found."));
        jdbc.sql("""
                INSERT INTO update_download_stat_daily(release_id, day, file_download_count, bytes_requested)
                VALUES (:releaseId, current_date, 1, :sizeBytes)
                ON CONFLICT (release_id, day) DO UPDATE SET
                    file_download_count = update_download_stat_daily.file_download_count + 1,
                    bytes_requested = update_download_stat_daily.bytes_requested + EXCLUDED.bytes_requested
                """).param("releaseId", file.releaseId()).param("sizeBytes", file.sizeBytes()).update();
        return storage.presignDownload(file.objectKey(), file.fileName());
    }

    private UpdateReleaseView findRelease(UUID id) {
        return jdbc.sql(summarySql("WHERE r.id = :id")).param("id", id)
                .query((rs, row) -> mapRelease(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_RELEASE_NOT_FOUND",
                        "The update release was not found."));
    }

    private UpdateFileView findFile(UUID releaseId, UUID fileId) {
        return jdbc.sql("SELECT * FROM update_file WHERE id = :fileId AND release_id = :releaseId")
                .param("fileId", fileId).param("releaseId", releaseId)
                .query((rs, row) -> mapFile(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                        "The update file was not found."));
    }

    private ExistingFile findExistingFile(UUID releaseId, String pathKey) {
        return jdbc.sql("""
                SELECT id FROM update_file WHERE release_id = :releaseId AND path_key = :pathKey
                """).param("releaseId", releaseId).param("pathKey", pathKey)
                .query((rs, row) -> new ExistingFile(rs.getObject("id", UUID.class))).optional().orElse(null);
    }

    private void restoreBasePath(UUID releaseId, UUID baseReleaseId, String pathKey) {
        jdbc.sql("""
                INSERT INTO update_file(id, release_id, target_path, path_key, action, overwrite_policy,
                                        upload_status, object_key, file_name, content_type, size_bytes,
                                        sha256, legacy_crc32, inherited)
                SELECT gen_random_uuid(), :releaseId, target_path, path_key, action, overwrite_policy,
                       upload_status, object_key, file_name, content_type, size_bytes,
                       sha256, legacy_crc32, TRUE
                FROM update_file WHERE release_id = :baseReleaseId AND path_key = :pathKey
                ON CONFLICT (release_id, path_key) DO NOTHING
                """).param("releaseId", releaseId).param("baseReleaseId", baseReleaseId)
                .param("pathKey", pathKey).update();
    }

    private EditableRelease editableRelease(UUID id) {
        EditableRelease release = jdbc.sql("""
                SELECT id, channel, status, base_release_id FROM update_release WHERE id = :id FOR UPDATE
                """).param("id", id).query((rs, row) -> new EditableRelease(
                        rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("status"),
                        rs.getObject("base_release_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPDATE_RELEASE_NOT_FOUND",
                        "The update release was not found."));
        if (!"DRAFT".equals(release.status()) && !"UPLOADING".equals(release.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "UPDATE_RELEASE_READ_ONLY",
                    "Published and retired update releases are read-only.");
        }
        return release;
    }

    private void refreshDraftStatus(UUID releaseId) {
        String status = countFiles(releaseId, "upload_status = 'PENDING'") > 0 ? "UPLOADING" : "DRAFT";
        jdbc.sql("UPDATE update_release SET status = :status WHERE id = :id")
                .param("status", status).param("id", releaseId).update();
    }

    private long fileCount(UUID releaseId) {
        return countFiles(releaseId, "TRUE");
    }

    private long countFiles(UUID releaseId, String condition) {
        return jdbc.sql("SELECT count(*) FROM update_file WHERE release_id = :releaseId AND " + condition)
                .param("releaseId", releaseId).query(Long.class).single();
    }

    private String summarySql(String where) {
        return """
                SELECT r.*, b.version AS base_version,
                       count(f.id) FILTER (WHERE f.action = 'UPSERT') AS file_count,
                       count(f.id) FILTER (WHERE f.inherited = FALSE) AS changed_count,
                       count(f.id) FILTER (WHERE f.upload_status = 'PENDING') AS pending_count
                FROM update_release r
                LEFT JOIN update_release b ON b.id = r.base_release_id
                LEFT JOIN update_file f ON f.release_id = r.id
                """ + "\n" + where + "\n" + """
                GROUP BY r.id, b.version
                ORDER BY r.created_at DESC
                """;
    }

    private UpdateReleaseView mapRelease(ResultSet rs) throws SQLException {
        var publishedAt = rs.getTimestamp("published_at");
        return new UpdateReleaseView(rs.getObject("id", UUID.class), rs.getString("version"),
                rs.getString("channel"), rs.getString("status"), rs.getObject("base_release_id", UUID.class),
                rs.getString("base_version"), rs.getString("release_notes_markdown"),
                rs.getString("minimum_launcher_version"), rs.getString("game_server_host"),
                (Integer) rs.getObject("game_server_port"), rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant(), rs.getLong("file_count"),
                rs.getLong("changed_count"), rs.getLong("pending_count"));
    }

    private UpdateFileView mapFile(ResultSet rs) throws SQLException {
        return new UpdateFileView(rs.getObject("id", UUID.class), rs.getString("target_path"),
                rs.getString("action"), rs.getString("overwrite_policy"), rs.getString("upload_status"),
                rs.getLong("size_bytes"), rs.getString("sha256"), (Integer) rs.getObject("legacy_crc32"),
                rs.getBoolean("inherited"));
    }

    private String manifestHash(UnsignedManifest manifest) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(manifest);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The update manifest could not be serialized.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
        }
    }

    private void validateGameServer(String host, Integer port) {
        String normalizedHost = blankToNull(host);
        if ((normalizedHost == null) != (port == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_SERVER_INCOMPLETE",
                    "Game server host and port must be provided together.");
        }
        if (normalizedHost != null && !normalizedHost.matches("[A-Za-z0-9.-]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_SERVER_HOST_INVALID",
                    "Game server host must be a DNS name or IPv4 address without a protocol or port.");
        }
    }

    private String normalizeChannel(String value) {
        String channel = value == null ? "STABLE" : value.trim().toUpperCase(Locale.ROOT);
        if (!"STABLE".equals(channel) && !"TEST".equals(channel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UPDATE_CHANNEL_INVALID",
                    "Update channel must be STABLE or TEST.");
        }
        return channel;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ExistingFile(UUID id) {}
    private record PendingFile(String objectKey, long sizeBytes, String status) {}
    private record ChangedFile(String targetPath, String pathKey, boolean inherited) {}
    private record EditableFile(String targetPath, String pathKey, String action, String overwritePolicy,
                                boolean inherited) {}
    private record EditableRelease(UUID id, String channel, String status, UUID baseReleaseId) {}
    private record DeletableRelease(UUID id, String version, String channel, String status) {}
    private record ReleaseState(UUID id, String version, String channel, String status, UUID baseReleaseId) {}
    private record PublishedRelease(UUID id, String version, String channel, String minimumLauncherVersion,
                                    String gameServerHost, Integer gameServerPort, Instant publishedAt) {}
    private record DownloadFile(String objectKey, String fileName, long sizeBytes, UUID releaseId) {}
    record LegacySnapshot(String version, String gameServerHost, Integer gameServerPort, List<LegacyFile> files) {}
    record LegacyFile(String path, String overwritePolicy, int sizeBytes, int legacyCrc32, String objectKey) {}
    private record UnsignedManifest(int schemaVersion, UUID releaseId, String version, String channel,
                                    String minimumLauncherVersion, String gameServerHost, Integer gameServerPort,
                                    Instant publishedAt, List<ManifestFile> files) {}
}
