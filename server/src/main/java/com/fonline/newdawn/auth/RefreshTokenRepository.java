package com.fonline.newdawn.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {
    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO refresh_token(user_id, family_id, token_hash, expires_at)
                VALUES (:userId, :familyId, :tokenHash, :expiresAt)
                """)
                .param("userId", userId).param("familyId", familyId).param("tokenHash", tokenHash)
                .param("expiresAt", Timestamp.from(expiresAt)).update();
    }

    public Optional<RefreshTokenRecord> findForUpdate(String tokenHash) {
        return jdbc.sql("""
                SELECT id, user_id, family_id, expires_at, used_at, revoked_at
                FROM refresh_token WHERE token_hash = :tokenHash FOR UPDATE
                """).param("tokenHash", tokenHash).query(this::map).optional();
    }

    public void markUsed(UUID id) {
        jdbc.sql("UPDATE refresh_token SET used_at = now() WHERE id = :id AND used_at IS NULL")
                .param("id", id).update();
    }

    public void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, now()) WHERE family_id = :familyId")
                .param("familyId", familyId).update();
    }

    public void revokeByHash(String tokenHash) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, now()) WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash).update();
    }

    public void revokeUser(UUID userId) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, now()) WHERE user_id = :userId")
                .param("userId", userId).update();
    }

    public void deleteExpired() {
        jdbc.sql("DELETE FROM refresh_token WHERE expires_at < now() - interval '7 days'").update();
    }

    private RefreshTokenRecord map(ResultSet rs, int row) throws SQLException {
        var used = rs.getTimestamp("used_at");
        var revoked = rs.getTimestamp("revoked_at");
        return new RefreshTokenRecord(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                used == null ? null : used.toInstant(), revoked == null ? null : revoked.toInstant());
    }
}
