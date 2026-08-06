package com.fonline.newdawn.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.fonline.newdawn.wiki.WikiModels.*;

@Service
public class WikiService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final StorageService storage;
    private final AppProperties properties;
    private final AuditRepository audit;

    public WikiService(JdbcClient jdbc, ObjectMapper json, StorageService storage,
                       AppProperties properties, AuditRepository audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
    }

    public List<PageSummary> search(String query, UUID categoryId, int page, int size) {
        String q = query == null ? "" : query.trim();
        return jdbc.sql("""
                SELECT p.id, p.slug, p.category_id, c.name AS category,
                       r.title, r.summary, p.status, p.lock_version, p.updated_at,
                       CASE WHEN :emptyQuery THEN coalesce(r.summary, '')
                            ELSE ts_headline('simple', coalesce(r.content_markdown, ''), websearch_to_tsquery('simple', :query),
                                 'MaxWords=28, MinWords=10, StartSel=<mark>, StopSel=</mark>') END AS excerpt,
                       CASE WHEN :emptyQuery THEN 0 ELSE ts_rank_cd(p.search_vector, websearch_to_tsquery('simple', :query)) END AS rank
                FROM wiki_page p
                JOIN wiki_revision r ON r.id = p.published_revision_id
                LEFT JOIN wiki_category c ON c.id = p.category_id
                WHERE p.status = 'PUBLISHED'
                  AND coalesce(r.properties -> 'featuredAsPatchNote', 'false'::jsonb) <> 'true'::jsonb
                  AND (:categoryEmpty OR p.category_id = :categoryId OR c.parent_id = :categoryId)
                  AND (:emptyQuery OR p.search_vector @@ websearch_to_tsquery('simple', :query))
                ORDER BY rank DESC, lower(r.title)
                LIMIT :size OFFSET :offset
                """)
                .param("query", q).param("emptyQuery", q.isBlank())
                .param("categoryId", categoryId).param("categoryEmpty", categoryId == null)
                .param("size", size).param("offset", page * size)
                .query((rs, row) -> mapSummary(rs)).list();
    }

    public List<PageDetail> patchNotes() {
        return jdbc.sql("""
                SELECT p.id, p.slug, p.category_id, c.name AS category, p.locale, p.status,
                       p.lock_version, r.id AS revision_id, r.revision_number, r.title, r.summary,
                       r.content_markdown, r.properties::text AS properties, p.created_at, p.updated_at
                FROM wiki_page p
                JOIN wiki_revision r ON r.id = p.published_revision_id
                LEFT JOIN wiki_category c ON c.id = p.category_id
                WHERE p.status = 'PUBLISHED'
                  AND coalesce(r.properties -> 'featuredAsPatchNote', 'false'::jsonb) = 'true'::jsonb
                ORDER BY p.updated_at DESC
                LIMIT 100
                """).query((rs, row) -> mapCore(rs)).list().stream().map(this::detail).toList();
    }

    public PageDetail publicPage(String slug) {
        PageCore page = jdbc.sql("""
                SELECT p.id, p.slug, p.category_id, c.name AS category, p.locale, p.status,
                       p.lock_version, r.id AS revision_id, r.revision_number, r.title, r.summary,
                       r.content_markdown, r.properties::text AS properties, p.created_at, p.updated_at
                FROM wiki_page p
                JOIN wiki_revision r ON r.id = p.published_revision_id
                LEFT JOIN wiki_category c ON c.id = p.category_id
                WHERE p.slug = :slug AND p.status = 'PUBLISHED'
                """).param("slug", slug).query((rs, row) -> mapCore(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WIKI_PAGE_NOT_FOUND", "Wiki page not found."));
        return detail(page);
    }

    public PageDetail adminPage(UUID id) {
        PageCore page = jdbc.sql("""
                SELECT p.id, p.slug, p.category_id, c.name AS category, p.locale, p.status,
                       p.lock_version, r.id AS revision_id, r.revision_number, r.title, r.summary,
                       r.content_markdown, r.properties::text AS properties, p.created_at, p.updated_at
                FROM wiki_page p
                JOIN LATERAL (SELECT * FROM wiki_revision wr WHERE wr.page_id = p.id ORDER BY revision_number DESC LIMIT 1) r ON TRUE
                LEFT JOIN wiki_category c ON c.id = p.category_id
                WHERE p.id = :id
                """).param("id", id).query((rs, row) -> mapCore(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WIKI_PAGE_NOT_FOUND", "Wiki page not found."));
        return detail(page);
    }

    public List<PageSummary> adminPages() {
        return jdbc.sql("""
                SELECT p.id, p.slug, p.category_id, c.name AS category, r.title, r.summary,
                       coalesce(r.summary, '') AS excerpt, p.status, p.lock_version, p.updated_at
                FROM wiki_page p
                JOIN LATERAL (SELECT * FROM wiki_revision wr WHERE wr.page_id = p.id ORDER BY revision_number DESC LIMIT 1) r ON TRUE
                LEFT JOIN wiki_category c ON c.id = p.category_id
                ORDER BY p.updated_at DESC
                """).query((rs, row) -> mapSummary(rs)).list();
    }

    @Transactional
    public PageDetail create(PageWriteRequest request, AuthenticatedUser actor) {
        requireJsonObject(request.properties(), "properties");
        validateReferences(null, request);
        try {
            UUID pageId = jdbc.sql("""
                    INSERT INTO wiki_page(slug, page_type, category_id, locale, created_by)
                    VALUES (:slug, 'ARTICLE', :categoryId, :locale, :actor)
                    RETURNING id
                    """).param("slug", request.slug())
                    .param("categoryId", request.categoryId()).param("locale", request.locale()).param("actor", actor.id())
                    .query(UUID.class).single();
            UUID revisionId = insertRevision(pageId, 1, request, actor.id());
            insertRevisionLinks(pageId, revisionId, request);
            audit.record(actor.id(), "WIKI_PAGE_CREATED", "WIKI_PAGE", pageId, Map.of("revisionId", revisionId));
            return adminPage(pageId);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "WIKI_PAGE_CONFLICT", "Slug or category is invalid or already used.");
        }
    }

    @Transactional
    public PageDetail revise(UUID pageId, PageWriteRequest request, AuthenticatedUser actor) {
        if (request.expectedLockVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOCK_VERSION_REQUIRED", "expectedLockVersion is required when editing.");
        }
        requireJsonObject(request.properties(), "properties");
        validateReferences(pageId, request);
        int updated = jdbc.sql("""
                UPDATE wiki_page SET slug = :slug, page_type = 'ARTICLE', category_id = :categoryId, locale = :locale,
                                     lock_version = lock_version + 1, updated_at = now()
                WHERE id = :id AND lock_version = :expected
                """).param("slug", request.slug())
                .param("categoryId", request.categoryId()).param("locale", request.locale())
                .param("id", pageId).param("expected", request.expectedLockVersion()).update();
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "WIKI_EDIT_CONFLICT", "This page was edited by someone else. Reload it before saving.");

        int revisionNumber = jdbc.sql("SELECT coalesce(max(revision_number), 0) + 1 FROM wiki_revision WHERE page_id = :id")
                .param("id", pageId).query(Integer.class).single();
        UUID revisionId = insertRevision(pageId, revisionNumber, request, actor.id());
        insertRevisionLinks(pageId, revisionId, request);
        audit.record(actor.id(), "WIKI_REVISION_CREATED", "WIKI_PAGE", pageId,
                Map.of("revisionId", revisionId, "revisionNumber", revisionNumber));
        return adminPage(pageId);
    }

    @Transactional
    public PageDetail publish(UUID pageId, UUID revisionId, AuthenticatedUser actor) {
        int updated = jdbc.sql("""
                UPDATE wiki_page p
                SET published_revision_id = r.id, status = 'PUBLISHED', updated_at = now(), lock_version = lock_version + 1,
                    search_vector = setweight(to_tsvector('simple', coalesce(r.title, '')), 'A') ||
                                    setweight(to_tsvector('simple', coalesce(r.summary, '')), 'B') ||
                                    setweight(to_tsvector('simple', coalesce(r.content_markdown, '')), 'C') ||
                                    setweight(to_tsvector('simple', coalesce(r.properties::text, '')), 'D')
                FROM wiki_revision r
                WHERE p.id = :pageId AND r.id = :revisionId AND r.page_id = p.id
                """).param("pageId", pageId).param("revisionId", revisionId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "WIKI_REVISION_NOT_FOUND", "Revision does not belong to this page.");
        audit.record(actor.id(), "WIKI_PAGE_PUBLISHED", "WIKI_PAGE", pageId, Map.of("revisionId", revisionId));
        return adminPage(pageId);
    }

    @Transactional
    public void archive(UUID pageId, AuthenticatedUser actor) {
        int updated = jdbc.sql("UPDATE wiki_page SET status = 'ARCHIVED', updated_at = now(), lock_version = lock_version + 1 WHERE id = :id")
                .param("id", pageId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "WIKI_PAGE_NOT_FOUND", "Wiki page not found.");
        audit.record(actor.id(), "WIKI_PAGE_ARCHIVED", "WIKI_PAGE", pageId, Map.of());
    }

    @Transactional
    public void deletePage(UUID pageId, AuthenticatedUser actor) {
        ArticleForDeletion page = jdbc.sql("""
                SELECT p.slug, p.status, latest.title
                FROM wiki_page p
                JOIN LATERAL (
                    SELECT title FROM wiki_revision WHERE page_id = p.id ORDER BY revision_number DESC LIMIT 1
                ) latest ON TRUE
                WHERE p.id = :id FOR UPDATE OF p
                """).param("id", pageId).query((rs, row) -> new ArticleForDeletion(
                        rs.getString("slug"), rs.getString("status"), rs.getString("title")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "WIKI_PAGE_NOT_FOUND", "Wiki article not found."));

        List<AssetObjectForDeletion> assets = jdbc.sql("""
                SELECT DISTINCT asset.id, asset.object_key
                FROM media_asset asset
                JOIN wiki_revision_asset link ON link.asset_id = asset.id
                JOIN wiki_revision revision ON revision.id = link.revision_id
                WHERE revision.page_id = :pageId
                """).param("pageId", pageId).query((rs, row) -> new AssetObjectForDeletion(
                        rs.getObject("id", UUID.class), rs.getString("object_key"))).list();

        int removedIncomingRelations = jdbc.sql("DELETE FROM wiki_revision_relation WHERE target_page_id = :id")
                .param("id", pageId).update();
        jdbc.sql("DELETE FROM wiki_page WHERE id = :id").param("id", pageId).update();

        int deletedAssets = 0;
        for (AssetObjectForDeletion asset : assets) {
            boolean stillUsed = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM wiki_revision_asset WHERE asset_id = :assetId)
                    """).param("assetId", asset.id()).query(Boolean.class).single();
            if (!stillUsed) {
                storage.deleteWikiObject(asset.objectKey());
                jdbc.sql("DELETE FROM media_asset WHERE id = :id").param("id", asset.id()).update();
                deletedAssets += 1;
            }
        }

        audit.record(actor.id(), "WIKI_ARTICLE_DELETED", "WIKI_PAGE", pageId,
                Map.of("slug", page.slug(), "title", page.title(), "status", page.status(),
                        "removedIncomingRelations", removedIncomingRelations, "deletedAssets", deletedAssets));
    }

    public List<RevisionView> revisions(UUID pageId) {
        return jdbc.sql("""
                SELECT r.id, r.revision_number, r.title, r.change_note, r.created_by, u.username, r.created_at,
                       (r.id = p.published_revision_id) AS published
                FROM wiki_revision r JOIN wiki_page p ON p.id = r.page_id JOIN app_user u ON u.id = r.created_by
                WHERE r.page_id = :pageId ORDER BY r.revision_number DESC
                """).param("pageId", pageId).query((rs, row) -> new RevisionView(
                        rs.getObject("id", UUID.class), rs.getInt("revision_number"), rs.getString("title"),
                        rs.getString("change_note"), rs.getObject("created_by", UUID.class), rs.getString("username"),
                        rs.getTimestamp("created_at").toInstant(), rs.getBoolean("published"))).list();
    }

    public List<CategoryView> categories() {
        return jdbc.sql("SELECT id, parent_id, slug, name, description, sort_order FROM wiki_category ORDER BY sort_order, lower(name)")
                .query((rs, row) -> new CategoryView(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                        rs.getString("slug"), rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"))).list();
    }

    @Transactional
    public CategoryView createCategory(CategoryRequest request, AuthenticatedUser actor) {
        validateCategoryParent(null, request.parentId());
        try {
            CategoryView created = jdbc.sql("""
                    INSERT INTO wiki_category(parent_id, slug, name, description, sort_order)
                    VALUES (:parentId, :slug, :name, :description, :sortOrder)
                    RETURNING id, parent_id, slug, name, description, sort_order
                    """).param("parentId", request.parentId()).param("slug", request.slug()).param("name", request.name())
                    .param("description", request.description()).param("sortOrder", request.sortOrder())
                    .query((rs, row) -> new CategoryView(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                            rs.getString("slug"), rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"))).single();
            audit.record(actor.id(), "WIKI_CATEGORY_CREATED", "WIKI_CATEGORY", created.id(), Map.of("slug", created.slug()));
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_CONFLICT", "Category slug or parent is invalid.");
        }
    }

    @Transactional
    public CategoryView updateCategory(UUID categoryId, CategoryRequest request, AuthenticatedUser actor) {
        validateCategoryParent(categoryId, request.parentId());
        try {
            int updated = jdbc.sql("""
                    UPDATE wiki_category
                    SET parent_id = :parentId, slug = :slug, name = :name, description = :description,
                        sort_order = :sortOrder, updated_at = now()
                    WHERE id = :id
                    """).param("parentId", request.parentId()).param("slug", request.slug())
                    .param("name", request.name()).param("description", request.description())
                    .param("sortOrder", request.sortOrder()).param("id", categoryId).update();
            if (updated == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Wiki category not found.");
            }
            CategoryView category = category(categoryId);
            audit.record(actor.id(), "WIKI_CATEGORY_UPDATED", "WIKI_CATEGORY", categoryId,
                    Map.of("slug", category.slug(), "name", category.name()));
            return category;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_CONFLICT",
                    "Category slug or parent is invalid or already used.");
        }
    }

    @Transactional
    public AssetUploadTicket initiateAsset(AssetUploadRequest request, AuthenticatedUser actor) {
        if ("WIKI_IMAGE".equals(request.kind()) && !List.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/avif")
                .contains(request.contentType().toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_TYPE", "Supported image formats are PNG, JPEG, WebP, GIF and AVIF.");
        }
        UUID assetId = UUID.randomUUID();
        String key = "wiki/" + assetId + "/" + storage.safeFileName(request.fileName());
        jdbc.sql("""
                INSERT INTO media_asset(id, kind, object_key, file_name, content_type, size_bytes, sha256, alt_text, uploaded_by)
                VALUES (:id, :kind, :key, :fileName, :contentType, :sizeBytes, lower(:sha256), :altText, :actor)
                """).param("id", assetId).param("kind", request.kind()).param("key", key)
                .param("fileName", request.fileName()).param("contentType", request.contentType())
                .param("sizeBytes", request.sizeBytes()).param("sha256", request.sha256())
                .param("altText", request.altText()).param("actor", actor.id()).update();
        StorageService.UploadTicket ticket = storage.presignUpload(key, request.contentType(), request.sizeBytes(), request.sha256());
        return new AssetUploadTicket(assetId, ticket.url(), ticket.headers(), Instant.now().plus(properties.storage().uploadUrlTtl()));
    }

    @Transactional
    public AssetView completeAsset(UUID assetId, AuthenticatedUser actor) {
        var pending = jdbc.sql("""
                SELECT object_key, size_bytes, file_name, content_type FROM media_asset
                WHERE id = :id AND status = 'PENDING'
                """).param("id", assetId).query((rs, row) -> new PendingAsset(
                        rs.getString("object_key"), rs.getLong("size_bytes"), rs.getString("file_name"), rs.getString("content_type")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "Pending asset not found."));
        StorageService.StoredObject object = storage.head(pending.objectKey());
        if (object.sizeBytes() != pending.sizeBytes()) {
            jdbc.sql("UPDATE media_asset SET status = 'REJECTED' WHERE id = :id").param("id", assetId).update();
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_SIZE_MISMATCH", "Uploaded asset size does not match the declaration.");
        }
        jdbc.sql("UPDATE media_asset SET status = 'READY' WHERE id = :id").param("id", assetId).update();
        audit.record(actor.id(), "WIKI_ASSET_UPLOADED", "MEDIA_ASSET", assetId, Map.of("fileName", pending.fileName()));
        return standaloneAsset(assetId);
    }

    public URI assetLocation(UUID assetId) {
        AssetObject object = jdbc.sql("SELECT object_key, kind, file_name FROM media_asset WHERE id = :id AND status = 'READY'")
                .param("id", assetId).query((rs, row) -> new AssetObject(
                        rs.getString("object_key"), rs.getString("kind"), rs.getString("file_name"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "Wiki asset not found."));
        return "WIKI_IMAGE".equals(object.kind())
                ? storage.presignInline(object.objectKey())
                : storage.presignDownload(object.objectKey(), object.fileName());
    }

    private AssetView standaloneAsset(UUID assetId) {
        return jdbc.sql("""
                SELECT id, kind, file_name, content_type, size_bytes, sha256, alt_text
                FROM media_asset WHERE id = :id AND status = 'READY'
                """).param("id", assetId).query((rs, row) -> new AssetView(
                        rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("alt_text"), null, null, 0, assetUrl(assetId))).single();
    }

    private UUID insertRevision(UUID pageId, int number, PageWriteRequest request, UUID actorId) {
        return jdbc.sql("""
                INSERT INTO wiki_revision(page_id, revision_number, title, summary, content_markdown, properties, change_note, created_by)
                VALUES (:pageId, :number, :title, :summary, :markdown, CAST(:properties AS jsonb), :changeNote, :actor)
                RETURNING id
                """).param("pageId", pageId).param("number", number).param("title", request.title())
                .param("summary", request.summary()).param("markdown", request.contentMarkdown())
                .param("properties", stringify(request.properties())).param("changeNote", request.changeNote())
                .param("actor", actorId).query(UUID.class).single();
    }

    private void insertRevisionLinks(UUID pageId, UUID revisionId, PageWriteRequest request) {
        List<RelationInput> relations = request.relations() == null ? List.of() : request.relations();
        for (RelationInput relation : relations) {
            if (relation.targetPageId().equals(pageId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_RELATION", "A wiki page cannot link to itself as a dependency.");
            }
            jdbc.sql("""
                    INSERT INTO wiki_revision_relation(revision_id, target_page_id, relation_type, label, sort_order, metadata)
                    VALUES (:revisionId, :targetId, :type, :label, :sortOrder, CAST(:metadata AS jsonb))
                    """).param("revisionId", revisionId).param("targetId", relation.targetPageId())
                    .param("type", relation.relationType()).param("label", relation.label())
                    .param("sortOrder", relation.sortOrder()).param("metadata", stringify(objectOrEmpty(relation.metadata()))).update();
        }
        List<AssetInput> assets = request.assets() == null ? List.of() : request.assets();
        for (AssetInput asset : assets) {
            int inserted = jdbc.sql("""
                    INSERT INTO wiki_revision_asset(revision_id, asset_id, usage, caption, sort_order)
                    SELECT :revisionId, id, :usage, :caption, :sortOrder FROM media_asset
                    WHERE id = :assetId AND status = 'READY'
                    """).param("revisionId", revisionId).param("assetId", asset.assetId()).param("usage", asset.usage())
                    .param("caption", asset.caption()).param("sortOrder", asset.sortOrder()).update();
            if (inserted == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_NOT_READY", "A linked wiki asset is missing or not ready.");
        }
    }

    private void validateReferences(UUID pageId, PageWriteRequest request) {
        if (request.categoryId() != null) {
            boolean categoryExists = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM wiki_category WHERE id = :id AND parent_id IS NOT NULL)
                    """)
                    .param("id", request.categoryId()).query(Boolean.class).single();
            if (!categoryExists) throw new ApiException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_FOUND",
                    "Articles can only be assigned to a wiki subcategory.");
        }
        for (RelationInput relation : request.relations() == null ? List.<RelationInput>of() : request.relations()) {
            boolean targetExists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM wiki_page WHERE id = :id)")
                    .param("id", relation.targetPageId()).query(Boolean.class).single();
            if (!targetExists) throw new ApiException(HttpStatus.BAD_REQUEST, "RELATION_TARGET_NOT_FOUND", "A relation target does not exist.");
            requireJsonObject(objectOrEmpty(relation.metadata()), "relation metadata");
        }
    }

    private PageSummary mapSummary(ResultSet rs) throws SQLException {
        return new PageSummary(rs.getObject("id", UUID.class), rs.getString("slug"),
                rs.getObject("category_id", UUID.class), rs.getString("category"), rs.getString("title"),
                rs.getString("summary"), rs.getString("excerpt"), rs.getString("status"), rs.getInt("lock_version"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private PageCore mapCore(ResultSet rs) throws SQLException {
        return new PageCore(rs.getObject("id", UUID.class), rs.getString("slug"),
                rs.getObject("category_id", UUID.class), rs.getString("category"), rs.getString("locale"),
                rs.getString("status"), rs.getInt("lock_version"), rs.getObject("revision_id", UUID.class),
                rs.getInt("revision_number"), rs.getString("title"), rs.getString("summary"),
                rs.getString("content_markdown"), parseJson(rs.getString("properties")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private PageDetail detail(PageCore page) {
        return new PageDetail(page.id(), page.slug(), page.categoryId(), page.category(), page.locale(),
                page.status(), page.lockVersion(), page.revisionId(), page.revisionNumber(), page.title(), page.summary(),
                page.contentMarkdown(), page.properties(), relations(page.revisionId()), assets(page.revisionId()),
                page.createdAt(), page.updatedAt());
    }

    private List<RelationView> relations(UUID revisionId) {
        return jdbc.sql("""
                SELECT rel.id, rel.relation_type, rel.label, rel.sort_order, rel.metadata::text AS metadata,
                       target.id AS target_id, target.slug AS target_slug, latest.title AS target_title
                FROM wiki_revision_relation rel
                JOIN wiki_page target ON target.id = rel.target_page_id
                JOIN LATERAL (SELECT title FROM wiki_revision WHERE page_id = target.id ORDER BY revision_number DESC LIMIT 1) latest ON TRUE
                WHERE rel.revision_id = :revisionId ORDER BY rel.sort_order, rel.relation_type
                """).param("revisionId", revisionId).query((rs, row) -> new RelationView(
                        rs.getObject("id", UUID.class), rs.getString("relation_type"), rs.getString("label"),
                        rs.getInt("sort_order"), parseJson(rs.getString("metadata")), rs.getObject("target_id", UUID.class),
                        rs.getString("target_slug"), rs.getString("target_title"))).list();
    }

    private List<AssetView> assets(UUID revisionId) {
        return jdbc.sql("""
                SELECT a.id, a.kind, a.file_name, a.content_type, a.size_bytes, a.sha256, a.alt_text,
                       link.usage, link.caption, link.sort_order
                FROM wiki_revision_asset link JOIN media_asset a ON a.id = link.asset_id
                WHERE link.revision_id = :revisionId AND a.status = 'READY'
                ORDER BY link.sort_order, a.file_name
                """).param("revisionId", revisionId).query((rs, row) -> {
                    UUID assetId = rs.getObject("id", UUID.class);
                    return new AssetView(assetId, rs.getString("kind"), rs.getString("file_name"),
                            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                            rs.getString("alt_text"), rs.getString("usage"), rs.getString("caption"),
                            rs.getInt("sort_order"), assetUrl(assetId));
                }).list();
    }

    private String assetUrl(UUID id) {
        return "/api/v1/wiki/assets/" + id;
    }

    private JsonNode parseJson(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in database.", exception);
        }
    }

    private String stringify(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "JSON object could not be processed.");
        }
    }

    private JsonNode objectOrEmpty(JsonNode value) {
        return value == null ? json.createObjectNode() : value;
    }

    private void requireJsonObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JSON_OBJECT_REQUIRED", field + " must be a JSON object.");
        }
    }

    private void validateCategoryParent(UUID categoryId, UUID parentId) {
        if (parentId != null) {
            if (parentId.equals(categoryId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CATEGORY_PARENT_INVALID",
                        "A category cannot be its own parent.");
            }
            boolean rootExists = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM wiki_category WHERE id = :id AND parent_id IS NULL)
                    """).param("id", parentId).query(Boolean.class).single();
            if (!rootExists) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CATEGORY_PARENT_INVALID",
                        "A subcategory must belong directly to a main category.");
            }
            if (categoryId != null) {
                boolean hasChildren = jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM wiki_category WHERE parent_id = :id)
                        """).param("id", categoryId).query(Boolean.class).single();
                if (hasChildren) {
                    throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_HAS_CHILDREN",
                            "A main category with subcategories cannot become a subcategory.");
                }
            }
        } else if (categoryId != null) {
            boolean hasArticles = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM wiki_page WHERE category_id = :id)
                    """).param("id", categoryId).query(Boolean.class).single();
            if (hasArticles) {
                throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_HAS_ARTICLES",
                        "Move this category's articles before turning it into a main category.");
            }
        }
    }

    private CategoryView category(UUID categoryId) {
        return jdbc.sql("""
                SELECT id, parent_id, slug, name, description, sort_order
                FROM wiki_category WHERE id = :id
                """).param("id", categoryId).query((rs, row) -> new CategoryView(
                        rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                        rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                        rs.getInt("sort_order"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CATEGORY_NOT_FOUND", "Wiki category not found."));
    }

    private record PendingAsset(String objectKey, long sizeBytes, String fileName, String contentType) {}
    private record AssetObject(String objectKey, String kind, String fileName) {}
    private record AssetObjectForDeletion(UUID id, String objectKey) {}
    private record ArticleForDeletion(String slug, String status, String title) {}
    private record PageCore(UUID id, String slug, UUID categoryId, String category, String locale,
                            String status, int lockVersion, UUID revisionId, int revisionNumber, String title,
                            String summary, String contentMarkdown, JsonNode properties, Instant createdAt,
                            Instant updatedAt) {}
}
