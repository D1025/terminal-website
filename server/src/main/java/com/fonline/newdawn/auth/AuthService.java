package com.fonline.newdawn.auth;

import com.fonline.newdawn.common.ApiException;
import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.security.JwtService;
import com.fonline.newdawn.user.UserAccount;
import com.fonline.newdawn.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AppProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Transactional
    public Session login(String username, String password) {
        String normalizedUsername = username == null ? "" : username.trim();
        UserAccount user = users.findByUsername(normalizedUsername).orElse(null);
        if (user == null || !user.enabled() || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.");
        }
        return createSession(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_REQUIRED", "Refresh session is missing.");
        }
        RefreshTokenRecord stored = refreshTokens.findForUpdate(hash(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh session is invalid."));

        if (stored.usedAt() != null || stored.revokedAt() != null) {
            refreshTokens.revokeFamily(stored.familyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_REPLAY_DETECTED", "Refresh session was revoked.");
        }
        if (stored.expiresAt().isBefore(Instant.now())) {
            refreshTokens.revokeFamily(stored.familyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_EXPIRED", "Refresh session expired.");
        }

        UserAccount user = users.findById(stored.userId()).filter(UserAccount::enabled).orElse(null);
        if (user == null) {
            refreshTokens.revokeFamily(stored.familyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Account is disabled.");
        }
        refreshTokens.markUsed(stored.id());
        return createSession(user, stored.familyId());
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) refreshTokens.revokeByHash(hash(rawToken));
    }

    private Session createSession(UserAccount user, UUID familyId) {
        String refreshToken = randomToken();
        Instant refreshExpiresAt = Instant.now().plus(properties.jwt().refreshTtl());
        refreshTokens.create(user.id(), familyId, hash(refreshToken), refreshExpiresAt);
        JwtService.IssuedToken access = jwtService.issue(user);
        return new Session(access.value(), access.expiresAt(), refreshToken, refreshExpiresAt,
                new SessionUser(user.id(), user.username(), user.role().name()));
    }

    public String csrfToken() {
        return randomToken();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Session(String accessToken, Instant accessExpiresAt, String refreshToken,
                          Instant refreshExpiresAt, SessionUser user) {}
    public record SessionUser(UUID id, String username, String role) {}
}
