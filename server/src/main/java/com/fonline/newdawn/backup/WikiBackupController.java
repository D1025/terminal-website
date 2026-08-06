package com.fonline.newdawn.backup;

import com.fonline.newdawn.security.AuthenticatedUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.fonline.newdawn.backup.WikiBackupModels.*;

@RestController
@RequestMapping("/api/v1/admin/backup")
public class WikiBackupController {
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private final WikiBackupService backups;

    public WikiBackupController(WikiBackupService backups) {
        this.backups = backups;
    }

    @GetMapping("/status")
    public BackupStatus status() {
        return backups.status();
    }

    @GetMapping("/export")
    public ResponseEntity<InputStreamResource> export(@AuthenticationPrincipal AuthenticatedUser actor) throws IOException {
        BackupArchive archive = backups.createExport(actor);
        try {
            InputStreamResource body = new DeleteAfterCloseResource(Files.newInputStream(archive.path()), archive.path());
            return ResponseEntity.ok()
                    .contentType(ZIP)
                    .contentLength(archive.sizeBytes())
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(archive.fileName(), StandardCharsets.UTF_8).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(body);
        } catch (IOException exception) {
            Files.deleteIfExists(archive.path());
            throw exception;
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BackupImportResult importBackup(
            @RequestPart("file") MultipartFile file,
            @RequestParam("confirmation") String confirmation,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        return backups.importBackup(file, confirmation, actor);
    }

    private static final class DeleteAfterCloseResource extends InputStreamResource {
        private final java.nio.file.Path path;

        private DeleteAfterCloseResource(InputStream input, java.nio.file.Path path) {
            super(input);
            this.path = path;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream delegate = super.getInputStream();
            return new java.io.FilterInputStream(delegate) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        Files.deleteIfExists(path);
                    }
                }
            };
        }
    }
}
