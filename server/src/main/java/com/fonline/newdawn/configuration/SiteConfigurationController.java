package com.fonline.newdawn.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonline.newdawn.audit.AuditRepository;
import com.fonline.newdawn.common.ApiException;
import com.fonline.newdawn.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SiteConfigurationController {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditRepository audit;

    public SiteConfigurationController(JdbcClient jdbc, ObjectMapper json, AuditRepository audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @GetMapping("/api/v1/configuration")
    public Map<String, JsonNode> publicConfiguration() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value::text AS value FROM site_configuration ORDER BY key")
                .query((rs, row) -> Map.entry(rs.getString("key"), parse(rs.getString("value"))))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    @PutMapping("/api/v1/admin/configuration/{key}")
    @Transactional
    public JsonNode update(
            @PathVariable @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{1,119}") String key,
            @Valid @RequestBody ConfigurationRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        jdbc.sql("""
                INSERT INTO site_configuration(key, value, updated_at, updated_by)
                VALUES (:key, CAST(:value AS jsonb), now(), :actor)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now(), updated_by = excluded.updated_by
                """).param("key", key).param("value", stringify(request.value())).param("actor", actor.id()).update();
        audit.record(actor.id(), "CONFIGURATION_UPDATED", "SITE_CONFIGURATION", null, Map.of("key", key));
        return request.value();
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String stringify(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Configuration value is invalid.");
        }
    }

    public record ConfigurationRequest(@NotNull JsonNode value) {}
}
