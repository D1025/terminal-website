package com.fonline.newdawn.release;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fonline.newdawn.release.ReleaseModels.*;

@Service
public class ReleaseService {
    private final JdbcClient jdbc;
    private final StorageService storage;
    private final AppProperties properties;
    private final AuditRepository audit;

    public ReleaseService(JdbcClient jdbc, StorageService storage, AppProperties properties, AuditRepository audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
    }

    public List<ReleaseView> publicReleases(String platform, String channel) {
        return jdbc.sql("""
                SELECT * FROM client_release
                WHERE status = 'PUBLISHED' AND (:platformEmpty OR platform = :platform) AND (:channelEmpty OR channel = :channel)
                ORDER BY published_at DESC
                """).param("platform", platform == null ? "" : platform).param("platformEmpty", platform == null || platform.isBlank())
                .param("channel", channel == null ? "" : channel).param("channelEmpty", channel == null || channel.isBlank())
                .query((rs, row) -> map(rs, true)).list();
    }

    public ReleaseView latest(String platform, String channel) {
        return jdbc.sql("""
                SELECT * FROM client_release WHERE status = 'PUBLISHED' AND platform = :platform AND channel = :channel
                ORDER BY published_at DESC LIMIT 1
                """).param("platform", platform).param("channel", channel).query((rs, row) -> map(rs, true)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "No client release is available for this platform and channel."));
    }

    public List<ReleaseView> adminReleases() {
        return jdbc.sql("SELECT * FROM client_release ORDER BY created_at DESC")
                .query((rs, row) -> map(rs, false)).list();
    }

    @Transactional
    public ReleaseUploadTicket create(CreateReleaseRequest request, AuthenticatedUser actor) {
        UUID id = UUID.randomUUID();
        String objectKey = "releases/" + request.channel().toLowerCase() + "/" + request.platform().toLowerCase()
                + "/" + id + "/" + storage.safeFileName(request.fileName());
        try {
            jdbc.sql("""
                    INSERT INTO client_release(id, version, platform, channel, status, file_name, object_key,
                                               content_type, size_bytes, sha256, release_notes_markdown,
                                               minimum_launcher_version, created_by)
                    VALUES (:id, :version, :platform, :channel, 'UPLOADING', :fileName, :objectKey,
                            :contentType, :sizeBytes, lower(:sha256), :notes, :minimum, :actor)
                    """).param("id", id).param("version", request.version()).param("platform", request.platform())
                    .param("channel", request.channel()).param("fileName", request.fileName()).param("objectKey", objectKey)
                    .param("contentType", request.contentType()).param("sizeBytes", request.sizeBytes())
                    .param("sha256", request.sha256()).param("notes", request.releaseNotesMarkdown())
                    .param("minimum", request.minimumLauncherVersion()).param("actor", actor.id()).update();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_EXISTS", "This version, platform and channel already exist.");
        }
        var ticket = storage.presignUpload(objectKey, request.contentType(), request.sizeBytes(), request.sha256());
        audit.record(actor.id(), "CLIENT_RELEASE_CREATED", "CLIENT_RELEASE", id,
                Map.of("version", request.version(), "platform", request.platform(), "channel", request.channel()));
        return new ReleaseUploadTicket(find(id, false), ticket.url(), ticket.headers(),
                Instant.now().plus(properties.storage().uploadUrlTtl()));
    }

    @Transactional
    public ReleaseView complete(UUID id, AuthenticatedUser actor) {
        PendingRelease pending = jdbc.sql("""
                SELECT object_key, size_bytes FROM client_release WHERE id = :id AND status = 'UPLOADING'
                """).param("id", id).query((rs, row) -> new PendingRelease(rs.getString("object_key"), rs.getLong("size_bytes")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RELEASE_UPLOAD_NOT_FOUND", "Uploading release not found."));
        StorageService.StoredObject object = storage.head(pending.objectKey());
        if (object.sizeBytes() != pending.sizeBytes()) {
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_SIZE_MISMATCH", "Uploaded client size does not match the declaration.");
        }
        jdbc.sql("UPDATE client_release SET status = 'UPLOADED' WHERE id = :id").param("id", id).update();
        audit.record(actor.id(), "CLIENT_RELEASE_UPLOADED", "CLIENT_RELEASE", id, Map.of());
        return find(id, false);
    }

    @Transactional
    public ReleaseView publish(UUID id, AuthenticatedUser actor) {
        int updated = jdbc.sql("UPDATE client_release SET status = 'PUBLISHED', published_at = now() WHERE id = :id AND status = 'UPLOADED'")
                .param("id", id).update();
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "RELEASE_NOT_READY", "Release must finish uploading before publication.");
        audit.record(actor.id(), "CLIENT_RELEASE_PUBLISHED", "CLIENT_RELEASE", id, Map.of());
        return find(id, false);
    }

    @Transactional
    public void retire(UUID id, AuthenticatedUser actor) {
        int updated = jdbc.sql("UPDATE client_release SET status = 'RETIRED' WHERE id = :id AND status = 'PUBLISHED'")
                .param("id", id).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Published release not found.");
        audit.record(actor.id(), "CLIENT_RELEASE_RETIRED", "CLIENT_RELEASE", id, Map.of());
    }

    @Transactional
    public void discard(UUID id, AuthenticatedUser actor) {
        DraftRelease release = jdbc.sql("""
                SELECT object_key, version, platform, channel, status
                FROM client_release WHERE id = :id FOR UPDATE
                """).param("id", id).query((rs, row) -> new DraftRelease(
                        rs.getString("object_key"), rs.getString("version"), rs.getString("platform"),
                        rs.getString("channel"), rs.getString("status")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND",
                        "Client release not found."));
        storage.deleteObject(release.objectKey());
        jdbc.sql("DELETE FROM client_release WHERE id = :id").param("id", id).update();
        audit.record(actor.id(), "CLIENT_RELEASE_DISCARDED", "CLIENT_RELEASE", id,
                Map.of("version", release.version(), "platform", release.platform(), "channel", release.channel(),
                        "status", release.status()));
    }

    @Transactional
    public URI download(UUID id) {
        DownloadObject release = jdbc.sql("""
                SELECT object_key, file_name FROM client_release WHERE id = :id AND status = 'PUBLISHED'
                """).param("id", id).query((rs, row) -> new DownloadObject(rs.getString("object_key"), rs.getString("file_name")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Client release not found."));
        jdbc.sql("""
                INSERT INTO download_stat_daily(release_id, day, download_count) VALUES (:id, current_date, 1)
                ON CONFLICT (release_id, day) DO UPDATE SET download_count = download_stat_daily.download_count + 1
                """).param("id", id).update();
        return storage.presignDownload(release.objectKey(), release.fileName());
    }

    private ReleaseView find(UUID id, boolean publicView) {
        return jdbc.sql("SELECT * FROM client_release WHERE id = :id").param("id", id)
                .query((rs, row) -> map(rs, publicView)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Client release not found."));
    }

    private ReleaseView map(ResultSet rs, boolean publicView) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        var publishedAt = rs.getTimestamp("published_at");
        return new ReleaseView(id, rs.getString("version"), rs.getString("platform"), rs.getString("channel"),
                rs.getString("status"), rs.getString("file_name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("release_notes_markdown"),
                rs.getString("minimum_launcher_version"), rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant(),
                publicView ? "/api/v1/releases/" + id + "/download" : null);
    }

    private record PendingRelease(String objectKey, long sizeBytes) {}
    private record DraftRelease(String objectKey, String version, String platform, String channel, String status) {}
    private record DownloadObject(String objectKey, String fileName) {}
}
