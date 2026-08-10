package org.nexus.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Merchant entity representing a registered NexusChain merchant.
 *
 * <p>Each merchant owns API keys used to authenticate gateway requests and has
 * a designated NEX settlement wallet address for receiving payments.</p>
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique merchant code assigned at registration. */
    @Column(name = "merchant_code", unique = true, nullable = false, length = 64)
    private String merchantCode;

    /** Display name of the merchant. */
    @Column(name = "merchant_name", nullable = false, length = 128)
    private String merchantName;

    /** Contact email. */
    @Column(name = "email", nullable = false, length = 128)
    private String email;

    /** NEX settlement wallet address for receiving payments. */
    @Column(name = "settlement_address", nullable = false, length = 66)
    private String settlementAddress;

    /** KYC verification status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    /** API keys associated with this merchant. */
    @JsonIgnore
    @OneToMany(mappedBy = "merchant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<ApiKey> apiKeys = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Enumerations ---

    public enum VerificationStatus {
        PENDING, VERIFIED, REJECTED
    }

    /**
     * Nested API key entity for merchant authentication credentials.
     */
    @Entity
    @Table(name = "merchant_api_keys")
    public static class ApiKey {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "merchant_id", nullable = false)
        private Merchant merchant;

        /** Public API key identifier. */
        @Column(name = "api_key", unique = true, nullable = false, length = 128)
        private String apiKey;

        /** Hashed secret used for request signing. */
        @Column(name = "secret_hash", nullable = false, length = 256)
        private String secretHash;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Column(name = "created_at", nullable = false, updatable = false)
        private LocalDateTime createdAt;

        @PrePersist
        protected void onCreate() {
            this.createdAt = LocalDateTime.now();
        }

        // --- Getters and Setters ---

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Merchant getMerchant() { return merchant; }
        public void setMerchant(Merchant merchant) { this.merchant = merchant; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getSecretHash() { return secretHash; }
        public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMerchantCode() { return merchantCode; }
    public void setMerchantCode(String merchantCode) { this.merchantCode = merchantCode; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getSettlementAddress() { return settlementAddress; }
    public void setSettlementAddress(String settlementAddress) { this.settlementAddress = settlementAddress; }

    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(VerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }

    public Set<ApiKey> getApiKeys() { return apiKeys; }
    public void setApiKeys(Set<ApiKey> apiKeys) { this.apiKeys = apiKeys; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

