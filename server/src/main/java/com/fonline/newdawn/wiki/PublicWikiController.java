package com.fonline.newdawn.wiki;

import com.fonline.newdawn.configuration.TimedContentAccessService;
import com.fonline.newdawn.security.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.fonline.newdawn.wiki.WikiModels.*;

@Validated
@RestController
@RequestMapping("/api/v1/wiki")
public class PublicWikiController {
    private final WikiService wiki;
    private final TimedContentAccessService timedAccess;

    public PublicWikiController(WikiService wiki, TimedContentAccessService timedAccess) {
        this.wiki = wiki;
        this.timedAccess = timedAccess;
    }

    @GetMapping("/pages")
    public List<PageSummary> search(
            @RequestParam(defaultValue = "") @Size(max = 200) String q,
            @RequestParam(required = false) UUID category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireWikiAccess(user);
        return wiki.search(q, category, page, size);
    }

    @GetMapping("/patch-notes")
    public List<PageDetail> patchNotes() {
        return wiki.patchNotes();
    }

    @GetMapping("/pages/{slug}")
    public PageDetail page(@PathVariable String slug, @AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireWikiAccess(user);
        return wiki.publicPage(slug);
    }

    @GetMapping("/categories")
    public List<CategoryView> categories(@AuthenticationPrincipal AuthenticatedUser user) {
        timedAccess.requireWikiAccess(user);
        return wiki.categories();
    }

    @GetMapping("/assets/{id}")
    public ResponseEntity<Void> asset(@PathVariable UUID id) {
        URI location = wiki.assetLocation(id);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
