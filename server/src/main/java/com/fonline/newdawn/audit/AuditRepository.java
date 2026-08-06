package com.fonline.newdawn.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
public class AuditRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(UUID actorId, String action, String entityType, UUID entityId, Map<String, ?> details) {
        try {
            jdbc.sql("""
                    INSERT INTO audit_log(actor_id, action, entity_type, entity_id, details)
                    VALUES (:actorId, :action, :entityType, :entityId, CAST(:details AS jsonb))
                    """)
                    .param("actorId", actorId).param("action", action).param("entityType", entityType)
                    .param("entityId", entityId).param("details", objectMapper.writeValueAsString(details)).update();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit details could not be serialized.", exception);
        }
    }
}
