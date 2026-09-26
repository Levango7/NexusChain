package org.nexus.gateway.security.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 支付密码服务。
 *
 * <p>提供支付密码的设置、验证、更换、解锁功能。密码以 bcrypt 哈希存储，
 * 连续验证失败达到上限后自动锁定，锁定到期或管理员手动解锁后恢复。</p>
 *
 * <p>核心安全设计：
 * <ul>
 *   <li>bcrypt cost=10 作为默认哈希强度（设计文档决策 4）</li>
 *   <li>失败计数递增 + 自动锁定（fail-closed 策略）</li>
 *   <li>密码更换时校验旧密码 + 历史重复检查</li>
 *   <li>密码过期检查</li>
 * </ul></p>
 *
 * <p>来源：设计文档 §5.4.2 PaymentPasswordService。</p>
 */
@Service
public class PaymentPasswordService {

    private static final Logger log = LoggerFactory.getLogger(PaymentPasswordService.class);

    private static final int BCRYPT_COST = 10;

    private final MerchantPaymentPasswordRepository passwordRepository;
    private final PasswordSecurityConfigService configService;
    private final PasswordHistoryService historyService;
    private final BCryptPasswordEncoder passwordEncoder;

    public PaymentPasswordService(MerchantPaymentPasswordRepository passwordRepository,
                                  PasswordSecurityConfigService configService,
                                  PasswordHistoryService historyService) {
        this.passwordRepository = passwordRepository;
        this.configService = configService;
        this.historyService = historyService;
        this.passwordEncoder = new BCryptPasswordEncoder(BCRYPT_COST);
    }

    /**
     * 设置支付密码。
     *
     * <p>校验密码复杂度后，使用 bcrypt 哈希存储。如果商户已有密码记录则覆盖更新。</p>
     *
     * @param merchantId       商户 ID
     * @param plaintextPassword 明文密码
     * @param tenantId         租户 ID（用于获取安全策略配置）
     * @throws IllegalArgumentException 密码复杂度不足
     */
    @Transactional
    public void setPassword(Long merchantId, String plaintextPassword, String tenantId) {
        PasswordSecurityConfig config = configService.getConfig(tenantId);
        configService.validateComplexity(plaintextPassword, config);

        String hash = passwordEncoder.encode(plaintextPassword);
        Instant now = Instant.now();

        Optional<MerchantPaymentPassword> existing = passwordRepository.findByMerchantId(merchantId);
        MerchantPaymentPassword entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setPasswordHash(hash);
            entity.setBcryptCost(BCRYPT_COST);
            entity.setStatus(PasswordStatus.ACTIVE);
            entity.setFailedAttempts(0);
            entity.setLockedUntil(null);
            entity.setLastChangedAt(now);
        } else {
            entity = new MerchantPaymentPassword();
            entity.setMerchantId(merchantId);
            entity.setPasswordHash(hash);
            entity.setBcryptCost(BCRYPT_COST);
            entity.setStatus(PasswordStatus.ACTIVE);
            entity.setFailedAttempts(0);
            entity.setLastChangedAt(now);
            entity.setCreatedAt(now);
        }
        passwordRepository.save(entity);

        // 记录密码历史
        historyService.recordPassword(merchantId, hash);

        log.info("Payment password set for merchant {}", merchantId);
    }

    /**
     * 验证支付密码。
     *
     * <p>验证流程：
     * <ol>
     *   <li>检查密码记录是否存在（未设置密码则跳过）</li>
     *   <li>检查锁定状态（锁定中返回 LOCKED）</li>
     *   <li>检查密码过期（过期返回 EXPIRED）</li>
     *   <li>bcrypt 验证密码（成功重置失败计数，失败递增并可能触发锁定）</li>
     * </ol></p>
     *
     * @param merchantId       商户 ID
     * @param plaintextPassword 明文密码
     * @param tenantId         租户 ID
     * @return 验证结果
     */
    @Transactional
    public PasswordVerifyResult verifyPassword(Long merchantId, String plaintextPassword, String tenantId) {
        Optional<MerchantPaymentPassword> opt = passwordRepository.findByMerchantId(merchantId);
        if (opt.isEmpty()) {
            // 商户未设置支付密码，跳过验证（渐进式启用）
            return PasswordVerifyResult.skipped();
        }

        MerchantPaymentPassword entity = opt.get();

        // 检查锁定状态
        if (entity.getLockedUntil() != null && entity.getLockedUntil().isAfter(Instant.now())) {
            return PasswordVerifyResult.locked(entity.getLockedUntil());
        }

        // 检查密码过期
        PasswordSecurityConfig config = configService.getConfig(tenantId);
        if (entity.getLastChangedAt() != null) {
            Instant expiryTime = entity.getLastChangedAt()
                    .plus(config.getPasswordExpiryDays(), ChronoUnit.DAYS);
            if (expiryTime.isBefore(Instant.now())) {
                entity.setStatus(PasswordStatus.EXPIRED);
                passwordRepository.save(entity);
                return PasswordVerifyResult.expired();
            }
        }

        // bcrypt 验证密码
        if (passwordEncoder.matches(plaintextPassword, entity.getPasswordHash())) {
            // 验证成功，重置失败计数
            entity.setFailedAttempts(0);
            entity.setStatus(PasswordStatus.ACTIVE);
            entity.setLockedUntil(null);
            passwordRepository.save(entity);
            return PasswordVerifyResult.success();
        } else {
            // 验证失败，增加失败计数
            entity.setFailedAttempts(entity.getFailedAttempts() + 1);

            if (entity.getFailedAttempts() >= config.getMaxFailedAttempts()) {
                // 触发锁定
                entity.setStatus(PasswordStatus.LOCKED);
                entity.setLockedUntil(Instant.now()
                        .plus(config.getLockDurationMinutes(), ChronoUnit.MINUTES));
                passwordRepository.save(entity);
                log.warn("Payment password locked for merchant {} after {} failed attempts",
                        merchantId, entity.getFailedAttempts());
                return PasswordVerifyResult.locked(entity.getLockedUntil());
            }

            passwordRepository.save(entity);
            int remaining = config.getMaxFailedAttempts() - entity.getFailedAttempts();
            return PasswordVerifyResult.invalid(remaining);
        }
    }

    /**
     * 更改支付密码。
     *
     * <p>流程：
     * <ol>
     *   <li>验证旧密码（失败返回 OLD_PASSWORD_INVALID）</li>
     *   <li>校验新密码复杂度</li>
     *   <li>检查新密码是否与历史重复（PASSWORD_REUSED）</li>
     *   <li>更新密码哈希并记录历史</li>
     * </ol></p>
     *
     * @param merchantId       商户 ID
     * @param oldPassword      旧密码
     * @param newPassword      新密码
     * @param tenantId         租户 ID
     * @throws IllegalArgumentException 旧密码错误、密码复杂度不足、密码重复
     */
    @Transactional
    public void changePassword(Long merchantId, String oldPassword, String newPassword, String tenantId) {
        MerchantPaymentPassword entity = passwordRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("支付密码尚未设置"));

        // 验证旧密码
        if (!passwordEncoder.matches(oldPassword, entity.getPasswordHash())) {
            throw new IllegalArgumentException("OLD_PASSWORD_INVALID: 旧密码错误");
        }

        // 校验新密码复杂度
        PasswordSecurityConfig config = configService.getConfig(tenantId);
        configService.validateComplexity(newPassword, config);

        // 检查新密码是否与历史重复
        if (historyService.isPlaintextInHistory(merchantId, newPassword, config.getPasswordHistoryCount())) {
            throw new IllegalArgumentException(
                    "PASSWORD_REUSED: 新密码与最近 " + config.getPasswordHistoryCount() + " 个历史密码重复");
        }

        // 更新密码
        String newHash = passwordEncoder.encode(newPassword);
        entity.setPasswordHash(newHash);
        entity.setBcryptCost(BCRYPT_COST);
        entity.setStatus(PasswordStatus.ACTIVE);
        entity.setFailedAttempts(0);
        entity.setLockedUntil(null);
        entity.setLastChangedAt(Instant.now());
        passwordRepository.save(entity);

        // 记录密码历史
        historyService.recordPassword(merchantId, newHash);

        log.info("Payment password changed for merchant {}", merchantId);
    }

    /**
     * 解锁支付密码（管理员操作）。
     *
     * <p>清除锁定状态和失败计数，密码本身不变。商户可继续使用原密码。</p>
     *
     * @param merchantId 商户 ID
     */
    @Transactional
    public void unlockPassword(Long merchantId) {
        MerchantPaymentPassword entity = passwordRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("支付密码尚未设置"));

        entity.setStatus(PasswordStatus.ACTIVE);
        entity.setFailedAttempts(0);
        entity.setLockedUntil(null);
        passwordRepository.save(entity);

        log.info("Payment password unlocked for merchant {} (admin operation)", merchantId);
    }

    /**
     * 检查商户支付密码是否被锁定。
     *
     * @param merchantId 商户 ID
     * @return true 表示密码当前处于锁定状态
     */
    @Transactional(readOnly = true)
    public boolean isLocked(Long merchantId) {
        return passwordRepository.findByMerchantId(merchantId)
                .map(entity -> entity.getStatus() == PasswordStatus.LOCKED
                        || (entity.getLockedUntil() != null && entity.getLockedUntil().isAfter(Instant.now())))
                .orElse(false);
    }

    /**
     * 检查商户是否已设置支付密码。
     *
     * @param merchantId 商户 ID
     * @return true 表示已设置支付密码
     */
    @Transactional(readOnly = true)
    public boolean isPasswordSet(Long merchantId) {
        return passwordRepository.findByMerchantId(merchantId).isPresent();
    }

    // --- 验证结果 ---

    /**
     * 密码验证结果。
     */
    public static class PasswordVerifyResult {
        public enum Status { SUCCESS, INVALID, LOCKED, EXPIRED, SKIPPED }

        private final Status status;
        private final int remainingAttempts;
        private final Instant lockedUntil;

        private PasswordVerifyResult(Status status, int remainingAttempts, Instant lockedUntil) {
            this.status = status;
            this.remainingAttempts = remainingAttempts;
            this.lockedUntil = lockedUntil;
        }

        public static PasswordVerifyResult success() {
            return new PasswordVerifyResult(Status.SUCCESS, 0, null);
        }

        public static PasswordVerifyResult invalid(int remainingAttempts) {
            return new PasswordVerifyResult(Status.INVALID, remainingAttempts, null);
        }

        public static PasswordVerifyResult locked(Instant lockedUntil) {
            return new PasswordVerifyResult(Status.LOCKED, 0, lockedUntil);
        }

        public static PasswordVerifyResult expired() {
            return new PasswordVerifyResult(Status.EXPIRED, 0, null);
        }

        public static PasswordVerifyResult skipped() {
            return new PasswordVerifyResult(Status.SKIPPED, 0, null);
        }

        public Status getStatus() { return status; }
        public int getRemainingAttempts() { return remainingAttempts; }
        public Instant getLockedUntil() { return lockedUntil; }
    }
}