package com.fonline.newdawn.configuration;

import com.fonline.newdawn.common.ApiException;
import com.fonline.newdawn.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Service
public class TimedContentAccessService {
    public static final Instant WIKI_FALLBACK = Instant.parse("2026-09-08T22:00:00Z");
    public static final Instant DOWNLOAD_FALLBACK = Instant.parse("2026-09-08T22:00:00Z");

    private static final Logger log = LoggerFactory.getLogger(TimedContentAccessService.class);
    private final JdbcClient jdbc;

    public TimedContentAccessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requireWikiAccess(AuthenticatedUser user) {
        requireAccess("wikiUnlockAt", WIKI_FALLBACK, user, "Wiki");
    }

    public void requireDownloadAccess(AuthenticatedUser user) {
        requireAccess("downloadUnlockAt", DOWNLOAD_FALLBACK, user, "Client downloads");
    }

    public boolean isWikiPublic() {
        return isPublic("wikiUnlockAt", WIKI_FALLBACK);
    }

    public boolean isDownloadPublic() {
        return isPublic("downloadUnlockAt", DOWNLOAD_FALLBACK);
    }

    private void requireAccess(String key, Instant fallback, AuthenticatedUser user, String label) {
        if (user != null) return;
        Instant unlockAt = configuredInstant(key, fallback);
        if (Instant.now().isBefore(unlockAt)) {
            throw new ApiException(HttpStatus.LOCKED, "CONTENT_LOCKED",
                    label + " will become public at " + unlockAt + ".");
        }
    }

    private boolean isPublic(String key, Instant fallback) {
        return !Instant.now().isBefore(configuredInstant(key, fallback));
    }

    private Instant configuredInstant(String key, Instant fallback) {
        String configured = jdbc.sql("SELECT value #>> '{}' FROM site_configuration WHERE key = :key")
                .param("key", key).query(String.class).optional().orElse(null);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            return Instant.parse(configured);
        } catch (DateTimeParseException exception) {
            try {
                return java.time.OffsetDateTime.parse(configured).toInstant();
            } catch (DateTimeParseException ignored) {
                log.warn("Invalid {} configuration value. Falling back to {}.", key, fallback);
                return fallback;
            }
        }
    }
}
