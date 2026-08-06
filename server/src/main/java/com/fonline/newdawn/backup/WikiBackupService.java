package com.fonline.newdawn.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonline.newdawn.audit.AuditRepository;
import com.fonline.newdawn.common.ApiException;
import com.fonline.newdawn.security.AuthenticatedUser;
import com.fonline.newdawn.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.fonline.newdawn.backup.WikiBackupModels.*;

@Service
public class WikiBackupService {
    private static final Logger log = LoggerFactory.getLogger(WikiBackupService.class);
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final long MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 128 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 100_001;
    private static final int MAX_TOTAL_RECORDS = 1_000_000;
    private static final long MAX_ASSET_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TOTAL_ASSET_BYTES = 20L * 1024 * 1024 * 1024;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}(?:-[A-Z]{2})?");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern RELATION_TYPE = Pattern.compile("[A-Z0-9_:-]{2,80}");
    private static final Set<String> PAGE_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final Set<String> ASSET_KINDS = Set.of("WIKI_IMAGE", "WIKI_FILE");
    private static final Set<String> ASSET_USAGES = Set.of("INLINE", "HERO", "GALLERY", "ATTACHMENT");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final StorageService storage;
    private final AuditRepository audit;

    public WikiBackupService(JdbcClient jdbc, ObjectMapper json, StorageService storage, AuditRepository audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.storage = storage;
        this.audit = audit;
    }

    public BackupStatus status() {
        return jdbc.sql("""
                SELECT
                    (SELECT count(*) FROM app_user WHERE role = 'EDITOR') AS editors,
                    (SELECT count(*) FROM wiki_category) AS categories,
                    (SELECT count(*) FROM wiki_page) AS articles,
                    (SELECT count(*) FROM wiki_revision) AS revisions,
                    (SELECT count(*) FROM media_asset) AS assets
                """).query((rs, row) -> {
            int editors = rs.getInt("editors");
            int categories = rs.getInt("categories");
            int articles = rs.getInt("articles");
            int revisions = rs.getInt("revisions");
            int assets = rs.getInt("assets");
            return new BackupStatus(editors, categories, articles, revisions, assets,
                    editors == 0 && categories == 0 && articles == 0 && revisions == 0 && assets == 0);
        }).single();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public BackupArchive createExport(AuthenticatedUser actor) {
        ExportData export = exportData();
        Path archive = null;
        try {
            archive = Files.createTempFile("new-dawn-wiki-backup-", ".zip");
            try (OutputStream file = Files.newOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(file)) {
                zip.setLevel(Deflater.BEST_SPEED);
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                JsonGenerator generator = json.getFactory().createGenerator(zip);
                generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
                json.writerWithDefaultPrettyPrinter().writeValue(generator, export.manifest());
                generator.close();
                zip.closeEntry();

                for (ExportAsset asset : export.assets()) {
                    zip.putNextEntry(new ZipEntry(asset.backup().zipPath()));
                    storage.writeWikiObjectTo(asset.objectKey(), zip);
                    zip.closeEntry();
                }
            }
            String fileName = "new-dawn-wiki-backup-" + FILE_TIME.format(export.manifest().exportedAt()) + ".zip";
            long size = Files.size(archive);
            audit.record(actor.id(), "WIKI_BACKUP_EXPORTED", "WIKI_BACKUP", null,
                    counts(export.manifest()));
            return new BackupArchive(archive, fileName, size);
        } catch (IOException exception) {
            deleteTempFile(archive);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BACKUP_EXPORT_FAILED",
                    "The wiki backup could not be created.");
        } catch (RuntimeException exception) {
            deleteTempFile(archive);
            throw exception;
        }
    }

    @Transactional
    public BackupImportResult importBackup(MultipartFile upload, String confirmation, AuthenticatedUser actor) {
        if (!"IMPORT".equals(confirmation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKUP_CONFIRMATION_REQUIRED",
                    "Confirm the import by sending the value IMPORT.");
        }
        if (upload == null || upload.isEmpty()) {
            throw invalid("Choose a non-empty backup archive.");
        }
        if (upload.getSize() > MAX_ARCHIVE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "BACKUP_TOO_LARGE",
                    "The backup archive exceeds the 2 GB import limit.");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile("new-dawn-wiki-import-", ".zip");
            upload.transferTo(temporary);
            try (ZipFile zip = new ZipFile(temporary.toFile())) {
                BackupManifest manifest = readManifest(zip);
                validateManifest(manifest);
                validateEntries(zip, manifest);
                verifyAssets(zip, manifest.assets());
                return restore(zip, manifest, actor);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("The selected file is not a readable New Dawn wiki backup.");
        } catch (DataIntegrityViolationException exception) {
            throw invalid("The backup contains values that conflict with the target database schema.");
        } finally {
            deleteTempFile(temporary);
        }
    }

    private ExportData exportData() {
        List<EditorBackup> editors = jdbc.sql("""
                SELECT id, username, password_hash, enabled, created_at, updated_at
                FROM app_user WHERE role = 'EDITOR' ORDER BY lower(username)
                """).query((rs, row) -> new EditorBackup(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                rs.getBoolean("enabled"), instant(rs, "created_at"), instant(rs, "updated_at"))).list();

        List<CategoryBackup> categories = jdbc.sql("""
                SELECT id, parent_id, slug, name, description, sort_order, created_at, updated_at
                FROM wiki_category ORDER BY sort_order, lower(name)
                """).query((rs, row) -> new CategoryBackup(
                rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getString("slug"),
                rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"),
                instant(rs, "created_at"), instant(rs, "updated_at"))).list();

        List<PageBackup> pages = jdbc.sql("""
                SELECT id, slug, category_id, locale, status, published_revision_id, created_by,
                       created_at, updated_at, lock_version
                FROM wiki_page ORDER BY created_at, id
                """).query((rs, row) -> new PageBackup(
                rs.getObject("id", UUID.class), rs.getString("slug"), rs.getObject("category_id", UUID.class),
                rs.getString("locale"), rs.getString("status"), rs.getObject("published_revision_id", UUID.class),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("lock_version"))).list();

        List<RevisionBackup> revisions = jdbc.sql("""
                SELECT id, page_id, revision_number, title, summary, content_markdown, properties::text,
                       change_note, created_by, created_at
                FROM wiki_revision ORDER BY page_id, revision_number
                """).query((rs, row) -> new RevisionBackup(
                rs.getObject("id", UUID.class), rs.getObject("page_id", UUID.class), rs.getInt("revision_number"),
                rs.getString("title"), rs.getString("summary"), rs.getString("content_markdown"),
                parseJson(rs.getString("properties")), rs.getString("change_note"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"))).list();

        List<RelationBackup> relations = jdbc.sql("""
                SELECT id, revision_id, target_page_id, relation_type, label, sort_order, metadata::text
                FROM wiki_revision_relation ORDER BY revision_id, sort_order, id
                """).query((rs, row) -> new RelationBackup(
                rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class),
                rs.getObject("target_page_id", UUID.class), rs.getString("relation_type"), rs.getString("label"),
                rs.getInt("sort_order"), parseJson(rs.getString("metadata")))).list();

        List<ExportAsset> assets = jdbc.sql("""
                SELECT id, kind, object_key, file_name, content_type, size_bytes, sha256, alt_text,
                       uploaded_by, created_at
                FROM media_asset WHERE status = 'READY' ORDER BY created_at, id
                """).query((rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new ExportAsset(new AssetBackup(
                    id, rs.getString("kind"), rs.getString("file_name"), rs.getString("content_type"),
                    rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("alt_text"),
                    rs.getObject("uploaded_by", UUID.class), instant(rs, "created_at"), assetPath(id)),
                    rs.getString("object_key"));
        }).list();

        List<RevisionAssetBackup> revisionAssets = jdbc.sql("""
                SELECT revision_id, asset_id, usage, caption, sort_order
                FROM wiki_revision_asset ORDER BY revision_id, sort_order, asset_id
                """).query((rs, row) -> new RevisionAssetBackup(
                rs.getObject("revision_id", UUID.class), rs.getObject("asset_id", UUID.class),
                rs.getString("usage"), rs.getString("caption"), rs.getInt("sort_order"))).list();

        BackupManifest manifest = new BackupManifest(FORMAT, VERSION, Instant.now(), editors, categories, pages,
                revisions, relations, assets.stream().map(ExportAsset::backup).toList(), revisionAssets);
        return new ExportData(manifest, assets);
    }

    private BackupManifest readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
        if (entry == null || entry.isDirectory()) {
            throw invalid("The archive does not contain manifest.json.");
        }
        if (entry.getSize() > MAX_MANIFEST_BYTES) {
            throw invalid("The backup manifest is too large.");
        }
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_MANIFEST_BYTES) throw invalid("The backup manifest is too large.");
                bytes.write(buffer, 0, count);
            }
            return json.readValue(bytes.toByteArray(), BackupManifest.class);
        } catch (JsonProcessingException exception) {
            throw invalid("The backup manifest contains malformed JSON.");
        }
    }

    private void validateManifest(BackupManifest manifest) {
        if (manifest == null || !FORMAT.equals(manifest.format()) || manifest.version() != VERSION) {
            throw invalid("This archive uses an unsupported backup format or version.");
        }
        require(manifest.exportedAt() != null, "The export timestamp is missing.");
        List<EditorBackup> editors = requireList(manifest.editors(), "editors");
        List<CategoryBackup> categories = requireList(manifest.categories(), "categories");
        List<PageBackup> pages = requireList(manifest.pages(), "pages");
        List<RevisionBackup> revisions = requireList(manifest.revisions(), "revisions");
        List<RelationBackup> relations = requireList(manifest.relations(), "relations");
        List<AssetBackup> assets = requireList(manifest.assets(), "assets");
        List<RevisionAssetBackup> revisionAssets = requireList(manifest.revisionAssets(), "revisionAssets");

        long totalRecords = (long) editors.size() + categories.size() + pages.size() + revisions.size()
                + relations.size() + assets.size() + revisionAssets.size();
        require(totalRecords <= MAX_TOTAL_RECORDS, "The backup contains too many records.");

        uniqueIds(editors, EditorBackup::id, "editor");
        Set<String> editorNames = new HashSet<>();
        for (EditorBackup editor : editors) {
            requireText(editor.username(), 3, 80, "Editor username");
            require(editor.username().equals(editor.username().trim()), "Editor usernames cannot contain surrounding spaces.");
            require(editorNames.add(editor.username().toLowerCase(Locale.ROOT)), "The backup contains duplicate editor usernames.");
            requireText(editor.passwordHash(), 20, 255, "Editor password hash");
            require(editor.passwordHash().startsWith("$argon2"), "An editor password hash has an unsupported format.");
            require(editor.createdAt() != null && editor.updatedAt() != null, "An editor timestamp is missing.");
        }

        uniqueIds(categories, CategoryBackup::id, "category");
        Set<String> categorySlugs = new HashSet<>();
        Map<UUID, CategoryBackup> categoryById = categories.stream()
                .collect(Collectors.toMap(CategoryBackup::id, Function.identity()));
        for (CategoryBackup category : categories) {
            requireSlug(category.slug(), 120, "Category slug");
            require(categorySlugs.add(category.slug()), "The backup contains duplicate category slugs.");
            requireText(category.name(), 1, 160, "Category name");
            requireOptionalText(category.description(), 2000, "Category description");
            require(category.sortOrder() >= 0 && category.sortOrder() <= 10000, "A category sort order is invalid.");
            require(category.createdAt() != null && category.updatedAt() != null, "A category timestamp is missing.");
            if (category.parentId() != null) {
                CategoryBackup parent = categoryById.get(category.parentId());
                require(parent != null && !category.id().equals(category.parentId()), "A category parent is invalid.");
                require(parent.parentId() == null, "Only two category levels are supported.");
            }
        }

        Set<UUID> pageIds = uniqueIds(pages, PageBackup::id, "article");
        Set<String> pageSlugs = new HashSet<>();
        for (PageBackup page : pages) {
            requireSlug(page.slug(), 220, "Article slug");
            require(pageSlugs.add(page.slug()), "The backup contains duplicate article slugs.");
            require(page.locale() != null && LOCALE.matcher(page.locale()).matches(), "An article locale is invalid.");
            require(PAGE_STATUSES.contains(page.status()), "An article status is invalid.");
            require(page.lockVersion() >= 0, "An article lock version is invalid.");
            require(page.createdAt() != null && page.updatedAt() != null, "An article timestamp is missing.");
            if (page.categoryId() != null) {
                CategoryBackup category = categoryById.get(page.categoryId());
                require(category != null && category.parentId() != null,
                        "An article must reference an existing subcategory.");
            }
        }

        Set<UUID> revisionIds = uniqueIds(revisions, RevisionBackup::id, "revision");
        Set<String> pageRevisionNumbers = new HashSet<>();
        Map<UUID, RevisionBackup> revisionById = revisions.stream()
                .collect(Collectors.toMap(RevisionBackup::id, Function.identity()));
        Set<UUID> pagesWithRevisions = new HashSet<>();
        for (RevisionBackup revision : revisions) {
            require(pageIds.contains(revision.pageId()), "A revision references a missing article.");
            pagesWithRevisions.add(revision.pageId());
            require(revision.revisionNumber() >= 1, "A revision number is invalid.");
            require(pageRevisionNumbers.add(revision.pageId() + ":" + revision.revisionNumber()),
                    "An article contains duplicate revision numbers.");
            requireText(revision.title(), 1, 220, "Revision title");
            requireOptionalText(revision.summary(), 4000, "Revision summary");
            require(revision.contentMarkdown() != null && revision.contentMarkdown().length() <= 1_000_000,
                    "Revision content is missing or too large.");
            require(revision.properties() != null && revision.properties().isObject(),
                    "Revision properties must be a JSON object.");
            requireOptionalText(revision.changeNote(), 500, "Revision change note");
            require(revision.createdAt() != null, "A revision timestamp is missing.");
        }
        require(pagesWithRevisions.containsAll(pageIds), "Every article must contain at least one revision.");
        for (PageBackup page : pages) {
            if (page.publishedRevisionId() != null) {
                RevisionBackup revision = revisionById.get(page.publishedRevisionId());
                require(revision != null && page.id().equals(revision.pageId()),
                        "An article references an invalid published revision.");
            }
            require(!"PUBLISHED".equals(page.status()) || page.publishedRevisionId() != null,
                    "A published article must identify its published revision.");
        }

        uniqueIds(relations, RelationBackup::id, "relation");
        Set<String> relationKeys = new HashSet<>();
        for (RelationBackup relation : relations) {
            require(revisionIds.contains(relation.revisionId()) && pageIds.contains(relation.targetPageId()),
                    "A relation references a missing revision or article.");
            RevisionBackup source = revisionById.get(relation.revisionId());
            require(source != null && !source.pageId().equals(relation.targetPageId()),
                    "An article relation cannot target itself.");
            require(relation.relationType() != null && RELATION_TYPE.matcher(relation.relationType()).matches(),
                    "A relation type is invalid.");
            requireOptionalText(relation.label(), 180, "Relation label");
            require(relation.sortOrder() >= 0 && relation.sortOrder() <= 10000, "A relation sort order is invalid.");
            require(relation.metadata() != null && relation.metadata().isObject(),
                    "Relation metadata must be a JSON object.");
            require(relationKeys.add(relation.revisionId() + ":" + relation.targetPageId() + ":" + relation.relationType()),
                    "The backup contains duplicate relations.");
        }

        Set<UUID> assetIds = uniqueIds(assets, AssetBackup::id, "asset");
        Set<String> assetPaths = new HashSet<>();
        long totalAssetBytes = 0;
        for (AssetBackup asset : assets) {
            require(ASSET_KINDS.contains(asset.kind()), "An asset kind is invalid.");
            requireText(asset.fileName(), 1, 255, "Asset file name");
            requireText(asset.contentType(), 1, 160, "Asset content type");
            require(asset.sizeBytes() >= 1 && asset.sizeBytes() <= MAX_ASSET_BYTES, "An asset size is invalid.");
            totalAssetBytes += asset.sizeBytes();
            require(totalAssetBytes <= MAX_TOTAL_ASSET_BYTES, "The backup contains too much uncompressed asset data.");
            require(asset.sha256() != null && SHA_256.matcher(asset.sha256()).matches(), "An asset checksum is invalid.");
            requireOptionalText(asset.altText(), 500, "Asset alternative text");
            require(asset.createdAt() != null, "An asset timestamp is missing.");
            require(assetPath(asset.id()).equals(asset.zipPath()), "An asset archive path is invalid.");
            require(assetPaths.add(asset.zipPath()), "The backup contains duplicate asset paths.");
        }

        Set<String> revisionAssetKeys = new HashSet<>();
        for (RevisionAssetBackup link : revisionAssets) {
            require(revisionIds.contains(link.revisionId()) && assetIds.contains(link.assetId()),
                    "An asset link references a missing revision or asset.");
            require(ASSET_USAGES.contains(link.usage()), "An asset usage is invalid.");
            requireOptionalText(link.caption(), 500, "Asset caption");
            require(link.sortOrder() >= 0 && link.sortOrder() <= 10000, "An asset sort order is invalid.");
            require(revisionAssetKeys.add(link.revisionId() + ":" + link.assetId()),
                    "The backup contains duplicate revision asset links.");
        }

    }

    private void validateEntries(ZipFile zip, BackupManifest manifest) {
        Set<String> expected = new LinkedHashSet<>();
        expected.add(MANIFEST_ENTRY);
        manifest.assets().forEach(asset -> expected.add(asset.zipPath()));
        Set<String> actual = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            count += 1;
            require(count <= MAX_ZIP_ENTRIES, "The backup archive contains too many entries.");
            String name = entry.getName();
            require(!entry.isDirectory() && safeEntryName(name), "The backup contains an unsafe archive entry.");
            require(actual.add(name), "The backup contains duplicate archive entries.");
            require(expected.contains(name), "The backup contains an unexpected archive entry.");
        }
        require(actual.equals(expected), "One or more backup asset files are missing.");
    }

    private void verifyAssets(ZipFile zip, List<AssetBackup> assets) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        for (AssetBackup asset : assets) {
            ZipEntry entry = zip.getEntry(asset.zipPath());
            require(entry != null && !entry.isDirectory(), "A backup asset file is missing.");
            require(entry.getSize() < 0 || entry.getSize() == asset.sizeBytes(), "A backup asset size does not match its manifest.");
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream input = zip.getInputStream(entry)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    require(total <= asset.sizeBytes(), "A backup asset is larger than declared.");
                    digest.update(buffer, 0, count);
                }
            }
            require(total == asset.sizeBytes(), "A backup asset is smaller than declared.");
            require(asset.sha256().equals(HexFormat.of().formatHex(digest.digest())),
                    "A backup asset checksum does not match its manifest.");
        }
    }

    private BackupImportResult restore(ZipFile zip, BackupManifest manifest, AuthenticatedUser actor) throws IOException {
        jdbc.sql("""
                LOCK TABLE app_user, wiki_category, wiki_page, wiki_revision, wiki_revision_relation,
                           media_asset, wiki_revision_asset IN SHARE ROW EXCLUSIVE MODE
                """).update();
        BackupStatus target = status();
        if (!target.importAllowed()) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_TARGET_NOT_EMPTY",
                    "Import is allowed only when wiki data, wiki assets and editor accounts are all empty.");
        }

        Set<UUID> editorIds = manifest.editors().stream().map(EditorBackup::id).collect(Collectors.toSet());
        Set<String> existingUsernames = new HashSet<>();
        Set<UUID> existingUserIds = new HashSet<>();
        jdbc.sql("SELECT id, lower(username) AS username FROM app_user").query((rs, row) -> {
            existingUserIds.add(rs.getObject("id", UUID.class));
            existingUsernames.add(rs.getString("username"));
            return true;
        }).list();
        require(editorIds.stream().noneMatch(existingUserIds::contains),
                "An imported editor identifier conflicts with an existing administrator.");
        require(manifest.editors().stream().map(EditorBackup::username).map(value -> value.toLowerCase(Locale.ROOT))
                        .noneMatch(existingUsernames::contains),
                "An imported editor username conflicts with an existing administrator.");

        Map<UUID, String> assetKeys = new HashMap<>();
        for (AssetBackup asset : manifest.assets()) {
            String key = "wiki/" + asset.id() + "/" + storage.safeFileName(asset.fileName());
            require(!storage.wikiObjectExists(key), "A restored wiki asset would overwrite an existing storage object.");
            assetKeys.put(asset.id(), key);
        }

        List<String> restoredObjects = new ArrayList<>();
        registerStorageRollback(restoredObjects);

        for (EditorBackup editor : manifest.editors()) {
            jdbc.sql("""
                    INSERT INTO app_user(id, username, password_hash, role, enabled, central_admin,
                                         created_at, updated_at, created_by)
                    VALUES (:id, :username, :passwordHash, 'EDITOR', :enabled, FALSE,
                            :createdAt, :updatedAt, :createdBy)
                    """).param("id", editor.id()).param("username", editor.username())
                    .param("passwordHash", editor.passwordHash()).param("enabled", editor.enabled())
                    .param("createdAt", timestamp(editor.createdAt())).param("updatedAt", timestamp(editor.updatedAt()))
                    .param("createdBy", actor.id()).update();
        }

        for (CategoryBackup category : manifest.categories().stream().filter(value -> value.parentId() == null).toList()) {
            insertCategory(category);
        }
        for (CategoryBackup category : manifest.categories().stream().filter(value -> value.parentId() != null).toList()) {
            insertCategory(category);
        }

        for (PageBackup page : manifest.pages()) {
            jdbc.sql("""
                    INSERT INTO wiki_page(id, slug, page_type, category_id, locale, status, published_revision_id,
                                          created_by, created_at, updated_at, lock_version)
                    VALUES (:id, :slug, 'ARTICLE', :categoryId, :locale, :status, NULL,
                            :createdBy, :createdAt, :updatedAt, :lockVersion)
                    """).param("id", page.id()).param("slug", page.slug()).param("categoryId", page.categoryId())
                    .param("locale", page.locale()).param("status", page.status())
                    .param("createdBy", restoredCreator(page.createdBy(), editorIds, actor.id()))
                    .param("createdAt", timestamp(page.createdAt())).param("updatedAt", timestamp(page.updatedAt()))
                    .param("lockVersion", page.lockVersion()).update();
        }

        for (RevisionBackup revision : manifest.revisions()) {
            jdbc.sql("""
                    INSERT INTO wiki_revision(id, page_id, revision_number, title, summary, content_markdown,
                                              properties, change_note, created_by, created_at)
                    VALUES (:id, :pageId, :revisionNumber, :title, :summary, :contentMarkdown,
                            CAST(:properties AS jsonb), :changeNote, :createdBy, :createdAt)
                    """).param("id", revision.id()).param("pageId", revision.pageId())
                    .param("revisionNumber", revision.revisionNumber()).param("title", revision.title())
                    .param("summary", revision.summary()).param("contentMarkdown", revision.contentMarkdown())
                    .param("properties", stringify(revision.properties())).param("changeNote", revision.changeNote())
                    .param("createdBy", restoredCreator(revision.createdBy(), editorIds, actor.id()))
                    .param("createdAt", timestamp(revision.createdAt())).update();
        }

        for (RelationBackup relation : manifest.relations()) {
            jdbc.sql("""
                    INSERT INTO wiki_revision_relation(id, revision_id, target_page_id, relation_type,
                                                       label, sort_order, metadata)
                    VALUES (:id, :revisionId, :targetPageId, :relationType, :label, :sortOrder,
                            CAST(:metadata AS jsonb))
                    """).param("id", relation.id()).param("revisionId", relation.revisionId())
                    .param("targetPageId", relation.targetPageId()).param("relationType", relation.relationType())
                    .param("label", relation.label()).param("sortOrder", relation.sortOrder())
                    .param("metadata", stringify(relation.metadata())).update();
        }

        for (AssetBackup asset : manifest.assets()) {
            String key = assetKeys.get(asset.id());
            restoredObjects.add(key);
            try (InputStream input = zip.getInputStream(zip.getEntry(asset.zipPath()))) {
                storage.putWikiObject(key, asset.contentType(), asset.sizeBytes(), input);
            }
            jdbc.sql("""
                    INSERT INTO media_asset(id, kind, status, object_key, file_name, content_type, size_bytes,
                                            sha256, alt_text, uploaded_by, created_at)
                    VALUES (:id, :kind, 'READY', :objectKey, :fileName, :contentType, :sizeBytes,
                            :sha256, :altText, :uploadedBy, :createdAt)
                    """).param("id", asset.id()).param("kind", asset.kind()).param("objectKey", key)
                    .param("fileName", asset.fileName()).param("contentType", asset.contentType())
                    .param("sizeBytes", asset.sizeBytes()).param("sha256", asset.sha256())
                    .param("altText", asset.altText())
                    .param("uploadedBy", restoredCreator(asset.uploadedBy(), editorIds, actor.id()))
                    .param("createdAt", timestamp(asset.createdAt())).update();
        }

        for (RevisionAssetBackup link : manifest.revisionAssets()) {
            jdbc.sql("""
                    INSERT INTO wiki_revision_asset(revision_id, asset_id, usage, caption, sort_order)
                    VALUES (:revisionId, :assetId, :usage, :caption, :sortOrder)
                    """).param("revisionId", link.revisionId()).param("assetId", link.assetId())
                    .param("usage", link.usage()).param("caption", link.caption())
                    .param("sortOrder", link.sortOrder()).update();
        }

        for (PageBackup page : manifest.pages()) {
            if (page.publishedRevisionId() != null) {
                jdbc.sql("UPDATE wiki_page SET published_revision_id = :revisionId WHERE id = :id")
                        .param("revisionId", page.publishedRevisionId()).param("id", page.id()).update();
            }
        }
        jdbc.sql("""
                UPDATE wiki_page p
                SET search_vector = setweight(to_tsvector('simple', coalesce(r.title, '')), 'A') ||
                                    setweight(to_tsvector('simple', coalesce(r.summary, '')), 'B') ||
                                    setweight(to_tsvector('simple', coalesce(r.content_markdown, '')), 'C') ||
                                    setweight(to_tsvector('simple', coalesce(r.properties::text, '')), 'D')
                FROM wiki_revision r WHERE r.id = p.published_revision_id
                """).update();

        BackupImportResult result = new BackupImportResult(manifest.editors().size(), manifest.categories().size(),
                manifest.pages().size(), manifest.revisions().size(), manifest.relations().size(),
                manifest.assets().size());
        Map<String, Object> details = new HashMap<>(counts(manifest));
        details.put("sourceExportedAt", manifest.exportedAt().toString());
        audit.record(actor.id(), "WIKI_BACKUP_IMPORTED", "WIKI_BACKUP", null, details);
        return result;
    }

    private void insertCategory(CategoryBackup category) {
        jdbc.sql("""
                INSERT INTO wiki_category(id, parent_id, slug, name, description, sort_order, created_at, updated_at)
                VALUES (:id, :parentId, :slug, :name, :description, :sortOrder, :createdAt, :updatedAt)
                """).param("id", category.id()).param("parentId", category.parentId()).param("slug", category.slug())
                .param("name", category.name()).param("description", category.description())
                .param("sortOrder", category.sortOrder()).param("createdAt", timestamp(category.createdAt()))
                .param("updatedAt", timestamp(category.updatedAt())).update();
    }

    private void registerStorageRollback(List<String> restoredObjects) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                for (String objectKey : restoredObjects) {
                    try {
                        storage.deleteWikiObject(objectKey);
                    } catch (RuntimeException exception) {
                        log.error("Could not remove restored wiki object {} after transaction rollback", objectKey, exception);
                    }
                }
            }
        });
    }

    private UUID restoredCreator(UUID sourceCreator, Set<UUID> editorIds, UUID actorId) {
        return sourceCreator != null && editorIds.contains(sourceCreator) ? sourceCreator : actorId;
    }

    private Map<String, Object> counts(BackupManifest manifest) {
        Map<String, Object> values = new HashMap<>();
        values.put("editors", manifest.editors().size());
        values.put("categories", manifest.categories().size());
        values.put("articles", manifest.pages().size());
        values.put("revisions", manifest.revisions().size());
        values.put("relations", manifest.relations().size());
        values.put("assets", manifest.assets().size());
        return values;
    }

    private JsonNode parseJson(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored wiki JSON could not be parsed.", exception);
        }
    }

    private String stringify(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw invalid("Backup JSON data could not be serialized.");
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
        }
    }

    private <T> Set<UUID> uniqueIds(List<T> values, Function<T, UUID> id, String label) {
        Set<UUID> result = new HashSet<>();
        for (T value : values) {
            UUID identifier = value == null ? null : id.apply(value);
            require(identifier != null, "A " + label + " identifier is missing.");
            require(result.add(identifier), "The backup contains duplicate " + label + " identifiers.");
        }
        return result;
    }

    private <T> List<T> requireList(List<T> values, String label) {
        require(values != null, "The backup manifest is missing " + label + ".");
        return values;
    }

    private void requireSlug(String value, int maximum, String label) {
        require(value != null && value.length() <= maximum && SLUG.matcher(value).matches(), label + " is invalid.");
    }

    private void requireText(String value, int minimum, int maximum, String label) {
        require(value != null && value.length() >= minimum && value.length() <= maximum && !value.isBlank(),
                label + " is missing or too long.");
    }

    private void requireOptionalText(String value, int maximum, String label) {
        require(value == null || value.length() <= maximum, label + " is too long.");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WIKI_BACKUP", message);
    }

    private boolean safeEntryName(String value) {
        return value != null && !value.isBlank() && !value.startsWith("/") && !value.startsWith("\\")
                && !value.contains("..") && !value.contains("\\");
    }

    private String assetPath(UUID id) {
        return "assets/" + id + ".bin";
    }

    private void deleteTempFile(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not remove temporary wiki backup file {}", path, exception);
        }
    }

    private record ExportAsset(AssetBackup backup, String objectKey) {}
    private record ExportData(BackupManifest manifest, List<ExportAsset> assets) {}
}
