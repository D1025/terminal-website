package com.fonline.newdawn.user;

import com.fonline.newdawn.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql("""
                SELECT id, username, password_hash, role, enabled, central_admin, created_at
                FROM app_user WHERE lower(username) = lower(:username)
                """).param("username", username == null ? "" : username.trim()).query(this::map).optional();
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, username, password_hash, role, enabled, central_admin, created_at
                FROM app_user WHERE id = :id
                """).param("id", id).query(this::map).optional();
    }

    public List<UserAccount> findAll() {
        return jdbc.sql("""
                SELECT id, username, password_hash, role, enabled, central_admin, created_at
                FROM app_user ORDER BY central_admin DESC, lower(username)
                """).query(this::map).list();
    }

    public UserAccount create(String username, String passwordHash, Role role, boolean centralAdmin, UUID createdBy) {
        try {
            return jdbc.sql("""
                    INSERT INTO app_user(username, password_hash, role, central_admin, created_by)
                    VALUES (:username, :passwordHash, :role, :centralAdmin, :createdBy)
                    RETURNING id, username, password_hash, role, enabled, central_admin, created_at
                    """)
                    .param("username", username.trim())
                    .param("passwordHash", passwordHash)
                    .param("role", role.name())
                    .param("centralAdmin", centralAdmin)
                    .param("createdBy", createdBy)
                    .query(this::map).single();
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "An account with this username already exists.");
        }
    }

    public void setEnabled(UUID id, boolean enabled) {
        int updated = jdbc.sql("UPDATE app_user SET enabled = :enabled, updated_at = now() WHERE id = :id AND central_admin = FALSE")
                .param("enabled", enabled).param("id", id).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "EDITOR_NOT_FOUND", "Editor account not found.");
    }

    public void updatePassword(UUID id, String passwordHash) {
        int updated = jdbc.sql("UPDATE app_user SET password_hash = :passwordHash, updated_at = now() WHERE id = :id AND central_admin = FALSE")
                .param("passwordHash", passwordHash).param("id", id).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "EDITOR_NOT_FOUND", "Editor account not found.");
    }

    public boolean centralAdminExists() {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE central_admin = TRUE)").query(Boolean.class).single();
    }

    private UserAccount map(ResultSet rs, int row) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"), rs.getBoolean("central_admin"),
                rs.getTimestamp("created_at").toInstant());
    }
}
