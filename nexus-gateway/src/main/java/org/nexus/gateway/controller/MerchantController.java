package org.nexus.gateway.controller;

import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.Merchant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API for merchant registration, verification, and API key management.
 */
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    /**
     * Register a new merchant.
     *
     * @param request registration request body
     * @return created merchant entity (201)
     */
    @PostMapping("/register")
    public ResponseEntity<Merchant> register(@RequestBody RegisterRequest request) {
        Merchant merchant = merchantService.register(
                request.getMerchantName(),
                request.getEmail(),
                request.getSettlementAddress()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(merchant);
    }

    /**
     * Submit or update KYC verification status.
     *
     * @param id      merchant ID
     * @param request verification request
     * @return updated merchant entity
     */
    @PostMapping("/{id}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Merchant> verify(@PathVariable Long id, @RequestBody VerifyRequest request) {
        Merchant.VerificationStatus status = Merchant.VerificationStatus.valueOf(request.getStatus().toUpperCase());
        Merchant merchant = merchantService.verify(id, status);
        return ResponseEntity.ok(merchant);
    }

    /**
     * Generate a new API key pair for a merchant.
     *
     * @param id merchant ID
     * @return API key and secret (shown once)
     */
    @PostMapping("/{id}/api-keys")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> generateApiKey(@PathVariable Long id) {
        MerchantService.ApiKeyPair pair = merchantService.generateApiKey(id);
        Map<String, String> result = new HashMap<>();
        result.put("apiKey", pair.getApiKey());
        result.put("secret", pair.getSecret());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Revoke an API key.
     *
     * @param id      merchant ID
     * @param request contains the apiKey to revoke
     * @return 204 on success
     */
    @DeleteMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void revokeApiKey(@PathVariable Long id, @RequestBody RevokeRequest request) {
        merchantService.revokeApiKey(id, request.getApiKey());
    }

    /**
     * Query merchant details.
     *
     * @param id merchant ID
     * @return merchant entity
     */
    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getMerchant(@PathVariable Long id) {
        return merchantService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Request DTOs ---

    public static class RegisterRequest {
        @NotBlank
        private String merchantName;
        @NotBlank
        private String email;
        @NotBlank
        private String settlementAddress;

        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getSettlementAddress() { return settlementAddress; }
        public void setSettlementAddress(String settlementAddress) { this.settlementAddress = settlementAddress; }
    }

    public static class VerifyRequest {
        @NotBlank
        private String status;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class RevokeRequest {
        @NotBlank
        private String apiKey;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
