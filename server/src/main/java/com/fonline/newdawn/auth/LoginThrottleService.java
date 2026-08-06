package com.fonline.newdawn.auth;

import com.fonline.newdawn.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class LoginThrottleService {
    private static final int MAX_FAILURES = 5;
    private final JdbcClient jdbc;

    public LoginThrottleService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void assertAllowed(String remoteAddress, String username) {
        boolean blocked = fingerprints(remoteAddress, username).stream().anyMatch(fingerprint ->
                jdbc.sql("SELECT EXISTS(SELECT 1 FROM auth_login_throttle WHERE fingerprint = :fingerprint AND blocked_until > now())")
                        .param("fingerprint", fingerprint).query(Boolean.class).single());
        if (blocked) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED",
                    "Too many failed login attempts. Try again later.");
        }
    }

    @Transactional
    public void failure(String remoteAddress, String username) {
        for (String fingerprint : fingerprints(remoteAddress, username)) {
            jdbc.sql("""
                    INSERT INTO auth_login_throttle(fingerprint, failure_count, window_started_at, blocked_until, updated_at)
                    VALUES (:fingerprint, 1, now(), NULL, now())
                    ON CONFLICT (fingerprint) DO UPDATE SET
                        failure_count = CASE
                            WHEN auth_login_throttle.window_started_at < now() - interval '15 minutes' THEN 1
                            ELSE auth_login_throttle.failure_count + 1
                        END,
                        window_started_at = CASE
                            WHEN auth_login_throttle.window_started_at < now() - interval '15 minutes' THEN now()
                            ELSE auth_login_throttle.window_started_at
                        END,
                        blocked_until = CASE
                            WHEN (CASE
                                WHEN auth_login_throttle.window_started_at < now() - interval '15 minutes' THEN 1
                                ELSE auth_login_throttle.failure_count + 1
                            END) >= :maxFailures THEN now() + interval '15 minutes'
                            ELSE auth_login_throttle.blocked_until
                        END,
                        updated_at = now()
                    """).param("fingerprint", fingerprint).param("maxFailures", MAX_FAILURES).update();
        }
    }

    @Transactional
    public void success(String remoteAddress, String username) {
        jdbc.sql("DELETE FROM auth_login_throttle WHERE fingerprint IN (:fingerprints)")
                .param("fingerprints", fingerprints(remoteAddress, username)).update();
    }

    private List<String> fingerprints(String remoteAddress, String username) {
        String address = remoteAddress == null ? "unknown" : remoteAddress;
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase();
        return List.of(hash("ip:" + address), hash("ip-user:" + address + ":" + normalizedUsername));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
