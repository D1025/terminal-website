package com.fonline.newdawn.auth;

import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.security.JwtService;
import com.fonline.newdawn.user.Role;
import com.fonline.newdawn.user.UserAccount;
import com.fonline.newdawn.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository users;
    @Mock
    private RefreshTokenRepository refreshTokens;
    @Mock
    private JwtService jwtService;

    @Test
    void editorCanLoginWithPasswordCreatedByTheConfiguredEncoder() {
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        String rawPassword = "EditorCheck!2026";
        UUID editorId = UUID.randomUUID();
        UserAccount editor = new UserAccount(editorId, "field-editor", encoder.encode(rawPassword),
                Role.EDITOR, true, false, Instant.now());
        Instant accessExpiry = Instant.now().plusSeconds(600);

        when(users.findByUsername("field-editor")).thenReturn(Optional.of(editor));
        when(jwtService.issue(editor)).thenReturn(new JwtService.IssuedToken("access-token", accessExpiry));

        AuthService service = new AuthService(users, refreshTokens, encoder, jwtService, properties());
        AuthService.Session session = service.login("  field-editor  ", rawPassword);

        assertThat(session.user().id()).isEqualTo(editorId);
        assertThat(session.user().role()).isEqualTo("EDITOR");
        assertThat(session.accessToken()).isEqualTo("access-token");
        verify(users).findByUsername("field-editor");
        verify(refreshTokens).create(eq(editorId), any(UUID.class), any(String.class), any(Instant.class));
    }

    private AppProperties properties() {
        return new AppProperties("http://localhost:5173", "http://localhost:5173",
                new AppProperties.Jwt("https://newdawn.local", "new-dawn-api",
                        "test-secret-that-is-at-least-sixty-four-bytes-long-and-never-used-outside-tests-123456",
                        Duration.ofMinutes(10), Duration.ofDays(30)),
                new AppProperties.Cookies(false, "Strict"),
                new AppProperties.BootstrapAdmin("admin", "irrelevant-password"),
                new AppProperties.Storage("http://localhost:9000", "http://localhost:9000", "us-east-1",
                        "bucket", "key", "secret", true, Duration.ofMinutes(15), Duration.ofMinutes(15)));
    }
}
