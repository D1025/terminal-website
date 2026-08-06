package com.fonline.newdawn.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SecurityMaintenance {
    private final RefreshTokenRepository refreshTokens;
    private final JdbcClient jdbc;

    public SecurityMaintenance(RefreshTokenRepository refreshTokens, JdbcClient jdbc) {
        this.refreshTokens = refreshTokens;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT6H")
    @Transactional
    public void removeExpiredSecurityState() {
        refreshTokens.deleteExpired();
        jdbc.sql("DELETE FROM auth_login_throttle WHERE updated_at < now() - interval '2 days'").update();
    }
}
