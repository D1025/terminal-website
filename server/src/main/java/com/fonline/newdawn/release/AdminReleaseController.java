package com.fonline.newdawn.release;

import com.fonline.newdawn.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.fonline.newdawn.release.ReleaseModels.*;

@RestController
@RequestMapping("/api/v1/admin/releases")
public class AdminReleaseController {
    private final ReleaseService releases;

    public AdminReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    @GetMapping
    public List<ReleaseView> list() {
        return releases.adminReleases();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseUploadTicket create(@Valid @RequestBody CreateReleaseRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser actor) {
        return releases.create(request, actor);
    }

    @PostMapping("/{id}/complete")
    public ReleaseView complete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return releases.complete(id, actor);
    }

    @PostMapping("/{id}/publish")
    public ReleaseView publish(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return releases.publish(id, actor);
    }

    @PostMapping("/{id}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retire(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        releases.retire(id, actor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        releases.discard(id, actor);
    }
}
