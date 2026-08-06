package com.fonline.newdawn.update;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.fonline.newdawn.update.UpdateModels.UpdateManifest;

@RestController
@RequestMapping("/api/v1/updates")
public class PublicUpdateController {
    private final UpdateService updates;

    public PublicUpdateController(UpdateService updates) {
        this.updates = updates;
    }

    @GetMapping("/manifest")
    public ResponseEntity<UpdateManifest> manifest(@RequestParam(defaultValue = "STABLE") String channel) {
        UpdateManifest manifest = updates.manifest(channel);
        return ResponseEntity.ok()
                .eTag('"' + manifest.manifestSha256() + '"')
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic().mustRevalidate())
                .body(manifest);
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Void> download(@PathVariable UUID fileId) {
        URI location = updates.download(fileId);
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(location)
                .build();
    }
}
