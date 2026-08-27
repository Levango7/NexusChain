package org.nexus.gateway.controller.v2;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.nexus.gateway.MerchantService;
import org.nexus.gateway.apiversion.V2ErrorCode;
import org.nexus.gateway.apiversion.V2ErrorResponse;
import org.nexus.gateway.model.Merchant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * v2 商户 API（P4-T7）。
 *
 * <p>与 v1 行为一致，但路径前缀改为 {@code /api/v2/merchants}，
 * 错误响应采用 {@link V2ErrorResponse} 统一格式。</p>
 */
@RestController
@RequestMapping("/api/v2/merchants")
@Tag(name = "Merchant v2", description = "v2 商户 API：统一错误码")
public class MerchantV2Controller {

    private final MerchantService merchantService;

    public MerchantV2Controller(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @Operation(summary = "Register merchant (v2)")
    @PostMapping("/register")
    public ResponseEntity<Merchant> register(@Valid @RequestBody RegisterRequest request) {
        Merchant merchant = merchantService.register(
                request.getMerchantName(),
                request.getEmail(),
                request.getSettlementAddress()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(merchant);
    }

    @Operation(summary = "Verify merchant (v2)")
    @PostMapping("/{id}/verify")
    public ResponseEntity<Object> verify(@PathVariable Long id, @RequestBody VerifyRequest request) {
        try {
            Merchant.VerificationStatus status =
                    Merchant.VerificationStatus.valueOf(request.getStatus().toUpperCase());
            Merchant merchant = merchantService.verify(id, status);
            return ResponseEntity.ok(merchant);
        } catch (IllegalArgumentException e) {
            Map<String, Object> details = new HashMap<>();
            details.put("merchantId", id);
            details.put("invalidStatus", request.getStatus());
            return ResponseEntity.badRequest()
                    .body(V2ErrorResponse.of(V2ErrorCode.BAD_REQUEST.getCode(),
                            "Invalid verification status: " + request.getStatus(), details));
        }
    }

    @Operation(summary = "Generate API key (v2)")
    @PostMapping("/{id}/api-keys")
    public ResponseEntity<Map<String, String>> generateApiKey(@PathVariable Long id) {
        MerchantService.ApiKeyPair pair = merchantService.generateApiKey(id);
        Map<String, String> result = new HashMap<>();
        result.put("apiKey", pair.getApiKey());
        result.put("secret", pair.getSecret());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Operation(summary = "Revoke API key (v2)")
    @DeleteMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable Long id, @RequestBody RevokeRequest request) {
        merchantService.revokeApiKey(id, request.getApiKey());
    }

    @Operation(summary = "Get merchant (v2)")
    @GetMapping("/{id}")
    public ResponseEntity<Object> getMerchant(@PathVariable Long id) {
        return merchantService.findById(id)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> {
                    Map<String, Object> details = new HashMap<>();
                    details.put("merchantId", id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(V2ErrorResponse.of(V2ErrorCode.MERCHANT_NOT_FOUND.getCode(),
                                    "Merchant with id=" + id + " not found", details));
                });
    }

    // --- DTO ---

    public static class RegisterRequest {
        @jakarta.validation.constraints.NotBlank
        private String merchantName;
        @jakarta.validation.constraints.NotBlank
        private String email;
        @jakarta.validation.constraints.NotBlank
        private String settlementAddress;

        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getSettlementAddress() { return settlementAddress; }
        public void setSettlementAddress(String settlementAddress) { this.settlementAddress = settlementAddress; }
    }

    public static class VerifyRequest {
        @jakarta.validation.constraints.NotBlank
        private String status;
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class RevokeRequest {
        @jakarta.validation.constraints.NotBlank
        private String apiKey;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}