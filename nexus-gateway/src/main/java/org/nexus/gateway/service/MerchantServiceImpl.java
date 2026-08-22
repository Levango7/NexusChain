package org.nexus.gateway.service;

import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class MerchantServiceImpl implements MerchantService {

    private static final Logger log = LoggerFactory.getLogger(MerchantServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MerchantRepository merchantRepository;

    public MerchantServiceImpl(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Override
    @Transactional
    public Merchant register(String merchantName, String email, String settlementAddress) {
        Merchant merchant = new Merchant();
        merchant.setMerchantCode("M" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        merchant.setMerchantName(merchantName);
        merchant.setEmail(email);
        merchant.setSettlementAddress(settlementAddress);
        merchant.setVerificationStatus(Merchant.VerificationStatus.PENDING);

        Merchant saved = merchantRepository.save(merchant);
        log.info("Merchant registered: code={}, name={}", saved.getMerchantCode(), merchantName);
        return saved;
    }

    @Override
    @Transactional
    public Merchant verify(Long merchantId, Merchant.VerificationStatus status) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));
        merchant.setVerificationStatus(status);
        Merchant saved = merchantRepository.save(merchant);
        log.info("Merchant verified: id={}, status={}", merchantId, status);
        return saved;
    }

    @Override
    @Transactional
    public ApiKeyPair generateApiKey(Long merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));

        String apiKey = "cpk_" + UUID.randomUUID().toString().replace("-", "");
        String secret = generateSecret();

        Merchant.ApiKey key = new Merchant.ApiKey();
        key.setMerchant(merchant);
        key.setApiKey(apiKey);
        key.setSecretHash(hashSecret(secret));
        key.setActive(true);
        merchant.getApiKeys().add(key);
        merchantRepository.save(merchant);

        log.info("API key generated for merchant: id={}", merchantId);
        return new ApiKeyPair(apiKey, secret);
    }

    @Override
    @Transactional
    public void revokeApiKey(Long merchantId, String apiKey) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));
        merchant.getApiKeys().stream()
                .filter(k -> k.getApiKey().equals(apiKey))
                .findFirst()
                .ifPresent(k -> {
                    k.setActive(false);
                    merchantRepository.save(merchant);
                });
        log.info("API key revoked: merchantId={}, apiKey={}", merchantId, apiKey);
    }

    @Override
    public Optional<Merchant> findByApiKey(String apiKey) {
        return merchantRepository.findByActiveApiKey(apiKey);
    }

    @Override
    public Optional<Merchant> findById(Long merchantId) {
        return merchantRepository.findById(merchantId);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.GeneralSecurityException | java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("Failed to hash secret", e);
        }
    }
}
