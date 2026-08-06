package com.fonline.newdawn.update;

import com.fonline.newdawn.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.fonline.newdawn.update.UpdateModels.*;

@RestController
@RequestMapping("/api/v1/admin/updates")
public class AdminUpdateController {
    private final UpdateService updates;

    public AdminUpdateController(UpdateService updates) {
        this.updates = updates;
    }

    @GetMapping
    public List<UpdateReleaseView> list() {
        return updates.adminReleases();
    }

    @GetMapping("/{id}")
    public UpdateReleaseDetail detail(@PathVariable UUID id,
                                      @RequestParam(defaultValue = "false") boolean includeInherited,
                                      @RequestParam(defaultValue = "") String q) {
        return updates.detail(id, includeInherited, q);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateReleaseDetail create(@Valid @RequestBody CreateUpdateReleaseRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.create(request, actor);
    }

    @PostMapping("/{releaseId}/files")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateUploadTicket createFileUpload(@PathVariable UUID releaseId,
                                               @Valid @RequestBody CreateUpdateFileRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.createFileUpload(releaseId, request, actor);
    }

    @PostMapping("/{releaseId}/files/{fileId}/complete")
    public UpdateFileView completeFile(@PathVariable UUID releaseId, @PathVariable UUID fileId,
                                       @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.completeFile(releaseId, fileId, actor);
    }

    @PatchMapping("/{releaseId}/files/{fileId}")
    public UpdateReleaseDetail editFile(@PathVariable UUID releaseId, @PathVariable UUID fileId,
                                        @Valid @RequestBody EditUpdateFileRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.editFile(releaseId, fileId, request, actor);
    }

    @PostMapping("/{releaseId}/deletions")
    public UpdateReleaseDetail markDeleted(@PathVariable UUID releaseId,
                                           @Valid @RequestBody DeleteUpdatePathRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.markDeleted(releaseId, request, actor);
    }

    @DeleteMapping("/{releaseId}/files/{fileId}")
    public UpdateReleaseDetail revertChange(@PathVariable UUID releaseId, @PathVariable UUID fileId,
                                            @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.revertChange(releaseId, fileId, actor);
    }

    @PostMapping("/{releaseId}/publish")
    public UpdateReleaseDetail publish(@PathVariable UUID releaseId,
                                       @AuthenticationPrincipal AuthenticatedUser actor) {
        return updates.publish(releaseId, actor);
    }

    @DeleteMapping("/{releaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable UUID releaseId, @AuthenticationPrincipal AuthenticatedUser actor) {
        updates.discard(releaseId, actor);
    }
}
