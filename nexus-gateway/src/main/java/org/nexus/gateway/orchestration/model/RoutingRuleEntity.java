package org.nexus.gateway.orchestration.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Routing Rule persistent entity - write-through store backing {@code RoutingEngine}.
 *
 * <p>The engine keeps rules in memory for low-latency routing; every mutation is
 * mirrored into this table so operator-configured rules survive restarts.</p>
 *
 * <ul>
 *   <li>{@code conditionsJson}: {@link java.util.Map}&lt;String,String&gt; serialized as JSON</li>
 *   <li>{@code strategy}: {@link org.nexus.gateway.orchestration.routing.RoutingStrategy} enum name()</li>
 *   <li>{@code connectorsCsv}: ordered connector list joined by comma</li>
 * </ul>
 */
@Entity
@Table(name = "routing_rules")
public class RoutingRuleEntity {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> CONDITIONS_TYPE =
            new TypeReference<Map<String, String>>() {};

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 256)
    private String name;

    /** Conditions map serialized as JSON (max 1024 chars). */
    @Column(length = 1024)
    private String conditionsJson;

    /** {@link org.nexus.gateway.orchestration.routing.RoutingStrategy} enum name(). */
    @Column(nullable = false, length = 32)
    private String strategy;

    /** Ordered connector ids joined by comma (e.g. "chain,mock"). */
    @Column(length = 512)
    private String connectorsCsv;

    /** Higher priority wins when multiple rules match. */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getConnectorsCsv() { return connectorsCsv; }
    public void setConnectorsCsv(String connectorsCsv) { this.connectorsCsv = connectorsCsv; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Serialize a conditions map to its JSON column representation.
     *
     * @return JSON string, or {@code null} when {@code conditions} is {@code null}
     */
    public static String toConditionsJson(Map<String, String> conditions) {
        if (conditions == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(conditions);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize routing conditions", e);
        }
    }

    /**
     * Deserialize the JSON column back into a conditions map.
     *
     * @return parsed map; empty map when input is null/blank (rule matches everything)
     */
    public static Map<String, String> fromConditionsJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return OBJECT_MAPPER.readValue(json, CONDITIONS_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize routing conditions: " + e.getMessage(), e);
        }
    }
}