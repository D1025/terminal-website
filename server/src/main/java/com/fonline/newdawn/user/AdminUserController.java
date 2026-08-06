package com.fonline.newdawn.user;

import com.fonline.newdawn.audit.AuditRepository;
import com.fonline.newdawn.auth.RefreshTokenRepository;
import com.fonline.newdawn.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final AuditRepository audit;

    public AdminUserController(UserRepository users, RefreshTokenRepository refreshTokens,
                               PasswordEncoder passwords, AuditRepository audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.audit = audit;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    @PostMapping
    @Transactional
    public UserView createEditor(@Valid @RequestBody CreateEditorRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser actor) {
        UserAccount created = users.create(request.username(), passwords.encode(request.password()), Role.EDITOR, false, actor.id());
        audit.record(actor.id(), "EDITOR_CREATED", "USER", created.id(), Map.of("username", created.username()));
        return UserView.from(created);
    }

    @PatchMapping("/{id}/enabled")
    @Transactional
    public void enabled(@PathVariable UUID id, @Valid @RequestBody EnabledRequest request,
                        @AuthenticationPrincipal AuthenticatedUser actor) {
        users.setEnabled(id, request.enabled());
        if (!request.enabled()) refreshTokens.revokeUser(id);
        audit.record(actor.id(), request.enabled() ? "EDITOR_ENABLED" : "EDITOR_DISABLED", "USER", id, Map.of());
    }

    @PostMapping("/{id}/password")
    @Transactional
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request,
                              @AuthenticationPrincipal AuthenticatedUser actor) {
        users.updatePassword(id, passwords.encode(request.password()));
        refreshTokens.revokeUser(id);
        audit.record(actor.id(), "EDITOR_PASSWORD_RESET", "USER", id, Map.of());
    }

    public record CreateEditorRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Size(min = 12, max = 128) String password
    ) {}
    public record EnabledRequest(boolean enabled) {}
    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 128) String password) {}
    public record UserView(UUID id, String username, String role, boolean enabled, boolean centralAdmin, Instant createdAt) {
        static UserView from(UserAccount user) {
            return new UserView(user.id(), user.username(), user.role().name(), user.enabled(), user.centralAdmin(), user.createdAt());
        }
    }
}
