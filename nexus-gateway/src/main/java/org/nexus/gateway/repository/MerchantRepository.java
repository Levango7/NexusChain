package org.nexus.gateway.repository;

import org.nexus.gateway.model.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByMerchantCode(String merchantCode);

    @Query("SELECT m FROM Merchant m JOIN m.apiKeys k WHERE k.apiKey = :apiKey AND k.active = true")
    Optional<Merchant> findByActiveApiKey(@Param("apiKey") String apiKey);
}
