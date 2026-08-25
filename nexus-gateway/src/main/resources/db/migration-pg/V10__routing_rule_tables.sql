-- V10: Routing rules persistence (v2.37.0). RoutingEngine write-through store.
CREATE TABLE IF NOT EXISTS routing_rules (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(256),
    conditions_json VARCHAR(1024),
    strategy VARCHAR(32) NOT NULL,
    connectors_csv VARCHAR(512),
    priority INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);