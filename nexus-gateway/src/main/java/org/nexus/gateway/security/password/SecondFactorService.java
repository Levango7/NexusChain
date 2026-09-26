package org.nexus.gateway.security.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 二次验证服务（OTP/TOTP/EMAIL）。
 *
 * <p>生成 6 位数字 OTP 验证码，以 bcrypt 哈希存储，设置 5 分钟过期时间。
 * 验证码通过日志模拟发送（不实际发送短信/邮件），生产环境可替换为真实发送实现。</p>
 *
 * <p>来源：设计文档 §5.4.4 SecondFactorService。</p>
 */
@Service
public class SecondFactorService {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorService.class);

    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_MINUTES = 5;
    private static final int BCRYPT_COST = 10;

    private final SecondFactorRecordRepository recordRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;

    public SecondFactorService(SecondFactorRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
        this.passwordEncoder = new BCryptPasswordEncoder(BCRYPT_COST);
        this.secureRandom = new SecureRandom();
    }

    /**
     * 生成二次验证码。
     *
     * <p>生成 6 位数字 OTP，以 bcrypt 哈希存储，设置 5 分钟过期时间。
     * 通过日志模拟发送（不实际发送）。</p>
     *
     * @param merchantId 商户 ID
     * @param factorType 验证类型（OTP/TOTP/EMAIL）
     * @return 创建的验证记录
     */
    @Transactional
    public SecondFactorRecord generateSecondFactor(Long merchantId, FactorType factorType) {
        String code = generateOtpCode();
        String codeHash = passwordEncoder.encode(code);

        SecondFactorRecord record = new SecondFactorRecord();
        record.setMerchantId(merchantId);
        record.setFactorType(factorType);
        record.setCodeHash(codeHash);
        record.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        record.setConsumed(false);
        record.setCreatedAt(Instant.now());
        recordRepository.save(record);

        // 模拟发送验证码（日志记录，不实际发送）
        simulateSend(merchantId, factorType, code);

        log.info("Second factor generated for merchant {}, type {}", merchantId, factorType);
        return record;
    }

    /**
     * 验证二次验证码。
     *
     * <p>查询商户未消费且未过期的验证记录，使用 bcrypt matches 比对验证码。
     * 验证成功后标记 consumed=true。</p>
     *
     * @param merchantId 商户 ID
     * @param code       用户输入的验证码
     * @return true 表示验证成功
     */
    @Transactional
    public boolean verifySecondFactor(Long merchantId, String code) {
        Instant now = Instant.now();
        List<SecondFactorRecord> activeRecords = recordRepository.findActiveByMerchantId(merchantId, now);

        if (activeRecords.isEmpty()) {
            log.warn("No active second factor record for merchant {}", merchantId);
            return false;
        }

        for (SecondFactorRecord record : activeRecords) {
            if (passwordEncoder.matches(code, record.getCodeHash())) {
                record.setConsumed(true);
                recordRepository.save(record);
                log.info("Second factor verified for merchant {}", merchantId);
                return true;
            }
        }

        log.warn("Second factor verification failed for merchant {}", merchantId);
        return false;
    }

    /**
     * 生成 6 位数字 OTP 验证码。
     *
     * @return 6 位数字字符串
     */
    private String generateOtpCode() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * 模拟发送验证码（日志记录，不实际发送）。
     *
     * <p>生产环境应替换为真实的短信/邮件发送实现。</p>
     *
     * @param merchantId 商户 ID
     * @param factorType 验证类型
     * @param code       验证码
     */
    private void simulateSend(Long merchantId, FactorType factorType, String code) {
        switch (factorType) {
            case OTP -> log.info("[SIMULATE SMS] OTP code for merchant {}: {}", merchantId, code);
            case EMAIL -> log.info("[SIMULATE EMAIL] OTP code for merchant {}: {}", merchantId, code);
            case TOTP -> log.info("[TOTP] No delivery needed for merchant {} (client-side generation)", merchantId);
        }
    }
}