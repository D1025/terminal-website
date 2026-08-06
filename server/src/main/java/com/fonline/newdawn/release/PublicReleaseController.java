package com.fonline.newdawn.release;

import com.fonline.newdawn.configuration.TimedContentAccessService;
import com.fonline.newdawn.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fonline.newdawn.release.ReleaseModels.ReleaseView;

@RestController
@RequestMapping("/api/v1/releases")
public class PublicReleaseController {
    private final ReleaseService releases;
    private final TimedContentAccessService timedAccess;

    public PublicReleaseController(ReleaseService releases, TimedContentAccessService timedAccess) {
        this.releases = releases;
        this.timedAccess = timedAccess;
    }

    @GetMapping
    public List<ReleaseView> list(@RequestParam(required = false) String platform,
                                  @RequestParam(required = false) String channel,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireDownloadAccess(user);
        return releases.publicReleases(platform, channel);
    }

    @GetMapping("/latest")
    public ReleaseView latest(@RequestParam(defaultValue = "WINDOWS") String platform,
                              @RequestParam(defaultValue = "STABLE") String channel,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireDownloadAccess(user);
        return releases.latest(platform, channel);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Void> download(@PathVariable UUID id,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireDownloadAccess(user);
        URI location = releases.download(id);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    @GetMapping("/{id}/download-link")
    public Map<String, URI> downloadLink(@PathVariable UUID id,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireDownloadAccess(user);
        return Map.of("url", releases.download(id));
    }
}
