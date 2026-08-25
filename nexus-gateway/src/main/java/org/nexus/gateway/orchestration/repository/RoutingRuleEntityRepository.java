package org.nexus.gateway.orchestration.repository;

import org.nexus.gateway.orchestration.model.RoutingRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoutingRuleEntityRepository extends JpaRepository<RoutingRuleEntity, String> {}