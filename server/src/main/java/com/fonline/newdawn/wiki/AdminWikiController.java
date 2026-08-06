package com.fonline.newdawn.wiki;

import com.fonline.newdawn.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.fonline.newdawn.wiki.WikiModels.*;

@RestController
@RequestMapping("/api/v1/admin/wiki")
public class AdminWikiController {
    private final WikiService wiki;

    public AdminWikiController(WikiService wiki) {
        this.wiki = wiki;
    }

    @GetMapping("/pages")
    public List<PageSummary> pages() {
        return wiki.adminPages();
    }

    @GetMapping("/pages/{id}")
    public PageDetail page(@PathVariable UUID id) {
        return wiki.adminPage(id);
    }

    @PostMapping("/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageDetail create(@Valid @RequestBody PageWriteRequest request,
                             @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.create(request, actor);
    }

    @PutMapping("/pages/{id}")
    public PageDetail revise(@PathVariable UUID id, @Valid @RequestBody PageWriteRequest request,
                             @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.revise(id, request, actor);
    }

    @PostMapping("/pages/{id}/publish/{revisionId}")
    public PageDetail publish(@PathVariable UUID id, @PathVariable UUID revisionId,
                              @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.publish(id, revisionId, actor);
    }

    @PostMapping("/pages/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        wiki.archive(id, actor);
    }

    @DeleteMapping("/pages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        wiki.deletePage(id, actor);
    }

    @GetMapping("/pages/{id}/revisions")
    public List<RevisionView> revisions(@PathVariable UUID id) {
        return wiki.revisions(id);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView category(@Valid @RequestBody CategoryRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.createCategory(request, actor);
    }

    @PutMapping("/categories/{id}")
    public CategoryView reviseCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.updateCategory(id, request, actor);
    }

    @PostMapping("/assets/initiate")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetUploadTicket initiateAsset(@Valid @RequestBody AssetUploadRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.initiateAsset(request, actor);
    }

    @PostMapping("/assets/{id}/complete")
    public AssetView completeAsset(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return wiki.completeAsset(id, actor);
    }
}
