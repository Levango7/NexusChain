package org.nexus.gateway.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 设备指纹服务，负责指纹生成、记录/更新、黑名单管理与风险评估。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>基于客户端特征生成 SHA-256 指纹哈希</li>
 *   <li>记录或更新设备指纹（追踪关联地址、交易计数）</li>
 *   <li>黑名单管理（加入/移除）</li>
 *   <li>风险评估（基于关联地址数、交易次数、黑名单状态）</li>
 * </ul>
 */
@Service
public class DeviceFingerprintService {

    private static final Logger log = LoggerFactory.getLogger(DeviceFingerprintService.class);

    /** linkedAddresses 最大长度（字符），超出截断 */
    private static final int MAX_LINKED_ADDRESSES_LENGTH = 2048;

    /** 高风险关联地址阈值（>3 个不同地址为 HIGH） */
    private static final int HIGH_RISK_ADDRESS_THRESHOLD = 3;

    /** 中风险关联地址阈值（2-3 个不同地址为 MEDIUM） */
    private static final int MEDIUM_RISK_ADDRESS_THRESHOLD = 2;

    /** 高风险交易次数阈值（>100 为 HIGH） */
    private static final int HIGH_RISK_TX_THRESHOLD = 100;

    /** 中风险交易次数阈值（>50 为 MEDIUM） */
    private static final int MEDIUM_RISK_TX_THRESHOLD = 50;

    private final DeviceFingerprintRepository repository;

    public DeviceFingerprintService(DeviceFingerprintRepository repository) {
        this.repository = repository;
    }

    /**
     * 根据客户端特征生成 SHA-256 指纹哈希。
     *
     * <p>将所有参数拼接后计算 SHA-256，返回 hex 编码字符串。
     * null 参数按空字符串处理。</p>
     *
     * @param userAgent       浏览器 UA
     * @param ipAddress       IP 地址
     * @param acceptLanguage  Accept-Language
     * @param screenResolution 屏幕分辨率
     * @param timezone        时区
     * @param platform        平台
     * @return 64 字符的 hex 编码 SHA-256 哈希
     */
    public String generateFingerprintHash(String userAgent, String ipAddress, String acceptLanguage,
                                          String screenResolution, String timezone, String platform) {
        String raw = nullToEmpty(userAgent) + "|" +
                nullToEmpty(ipAddress) + "|" +
                nullToEmpty(acceptLanguage) + "|" +
                nullToEmpty(screenResolution) + "|" +
                nullToEmpty(timezone) + "|" +
                nullToEmpty(platform);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 记录或更新设备指纹。
     *
     * <p>不存在则创建新记录（firstSeenAt=now, lastSeenAt=now, transactionCount=1）；
     * 已存在则更新（lastSeenAt=now, transactionCount++, 追加新 payerAddress 到 linkedAddresses）。</p>
     *
     * @param fingerprintHash  指纹哈希
     * @param merchantId       商户 ID
     * @param payerAddress     支付方地址
     * @param userAgent        浏览器 UA
     * @param ipAddress        IP 地址
     * @param acceptLanguage   Accept-Language
     * @param screenResolution 屏幕分辨率
     * @param timezone         时区
     * @param platform         平台
     * @return 记录或更新后的 DeviceFingerprint
     */
    public DeviceFingerprint recordOrUpdate(String fingerprintHash, Long merchantId, String payerAddress,
                                             String userAgent, String ipAddress, String acceptLanguage,
                                             String screenResolution, String timezone, String platform) {
        Optional<DeviceFingerprint> existing = repository.findByFingerprintHash(fingerprintHash);

        if (existing.isEmpty()) {
            // 创建新记录
            DeviceFingerprint fp = new DeviceFingerprint();
            fp.setFingerprintHash(fingerprintHash);
            fp.setMerchantId(merchantId);
            fp.setPayerAddress(payerAddress);
            fp.setUserAgent(userAgent);
            fp.setIpAddress(ipAddress);
            fp.setAcceptLanguage(acceptLanguage);
            fp.setScreenResolution(screenResolution);
            fp.setTimezone(timezone);
            fp.setPlatform(platform);
            LocalDateTime now = LocalDateTime.now();
            fp.setFirstSeenAt(now);
            fp.setLastSeenAt(now);
            fp.setTransactionCount(1);
            fp.setRiskLevel(DeviceFingerprint.RiskLevel.LOW);
            fp.setBlacklisted(false);
            log.info("创建新设备指纹: hash={}, payerAddress={}", fingerprintHash, payerAddress);
            return repository.save(fp);
        }

        // 更新已有记录
        DeviceFingerprint fp = existing.get();
        fp.setLastSeenAt(LocalDateTime.now());
        fp.setTransactionCount(fp.getTransactionCount() + 1);

        // 如果 payerAddress 与已有记录不同且非空，追加到 linkedAddresses
        if (payerAddress != null && !payerAddress.equals(fp.getPayerAddress())) {
            String updatedLinked = appendLinkedAddress(fp.getLinkedAddresses(), payerAddress);
            fp.setLinkedAddresses(updatedLinked);
            log.info("设备指纹关联新地址: hash={}, newAddress={}", fingerprintHash, payerAddress);
        }

        log.debug("更新设备指纹: hash={}, transactionCount={}", fingerprintHash, fp.getTransactionCount());
        return repository.save(fp);
    }

    /**
     * 检查指纹是否在黑名单中。
     *
     * @param fingerprintHash 指纹哈希
     * @return true 如果在黑名单中或指纹不存在但曾被标记
     */
    public boolean isBlacklisted(String fingerprintHash) {
        Optional<DeviceFingerprint> fp = repository.findByFingerprintHash(fingerprintHash);
        return fp.isPresent() && fp.get().isBlacklisted();
    }

    /**
     * 将指纹加入黑名单。
     *
     * @param fingerprintHash 指纹哈希
     * @param reason          加入原因
     */
    public void blacklist(String fingerprintHash, String reason) {
        Optional<DeviceFingerprint> existing = repository.findByFingerprintHash(fingerprintHash);
        if (existing.isEmpty()) {
            log.warn("黑名单操作失败：指纹不存在, hash={}, reason={}", fingerprintHash, reason);
            return;
        }
        DeviceFingerprint fp = existing.get();
        fp.setBlacklisted(true);
        fp.setRiskLevel(DeviceFingerprint.RiskLevel.BLACKLISTED);
        repository.save(fp);
        log.warn("设备指纹加入黑名单: hash={}, reason={}", fingerprintHash, reason);
    }

    /**
     * 移除指纹的黑名单标记。
     *
     * @param fingerprintHash 指纹哈希
     */
    public void unblacklist(String fingerprintHash) {
        Optional<DeviceFingerprint> existing = repository.findByFingerprintHash(fingerprintHash);
        if (existing.isEmpty()) {
            log.warn("移除黑名单失败：指纹不存在, hash={}", fingerprintHash);
            return;
        }
        DeviceFingerprint fp = existing.get();
        fp.setBlacklisted(false);
        fp.setRiskLevel(DeviceFingerprint.RiskLevel.LOW);
        repository.save(fp);
        log.info("设备指纹移除黑名单: hash={}", fingerprintHash);
    }

    /**
     * 评估设备指纹的风险等级。
     *
     * <p>判断顺序：BLACKLISTED > HIGH > MEDIUM > LOW</p>
     * <ul>
     *   <li>BLACKLISTED: 已在黑名单中</li>
     *   <li>HIGH: 关联 >3 个不同 payerAddress 或交易次数 >100</li>
     *   <li>MEDIUM: 关联 2-3 个不同 payerAddress 或交易次数 >50</li>
     *   <li>LOW: 正常</li>
     * </ul>
     *
     * @param fingerprintHash 指纹哈希
     * @return 风险等级，不存在返回 LOW
     */
    public DeviceFingerprint.RiskLevel assessRisk(String fingerprintHash) {
        Optional<DeviceFingerprint> existing = repository.findByFingerprintHash(fingerprintHash);
        if (existing.isEmpty()) {
            return DeviceFingerprint.RiskLevel.LOW;
        }

        DeviceFingerprint fp = existing.get();

        // BLACKLISTED 优先判断
        if (fp.isBlacklisted()) {
            return DeviceFingerprint.RiskLevel.BLACKLISTED;
        }

        int linkedAddressCount = countLinkedAddresses(fp);

        // HIGH: 关联 >3 个地址 或 交易次数 >100
        if (linkedAddressCount > HIGH_RISK_ADDRESS_THRESHOLD || fp.getTransactionCount() > HIGH_RISK_TX_THRESHOLD) {
            return DeviceFingerprint.RiskLevel.HIGH;
        }

        // MEDIUM: 关联 2-3 个地址 或 交易次数 >50
        if (linkedAddressCount >= MEDIUM_RISK_ADDRESS_THRESHOLD || fp.getTransactionCount() > MEDIUM_RISK_TX_THRESHOLD) {
            return DeviceFingerprint.RiskLevel.MEDIUM;
        }

        return DeviceFingerprint.RiskLevel.LOW;
    }

    /**
     * 查找与某个 payerAddress 关联的所有设备指纹。
     *
     * @param payerAddress 支付方地址
     * @return 关联的设备指纹列表
     */
    public List<DeviceFingerprint> getLinkedDevices(String payerAddress) {
        return repository.findByPayerAddress(payerAddress);
    }

    /**
     * 获取所有黑名单设备。
     *
     * @return 黑名单设备指纹列表
     */
    public List<DeviceFingerprint> getBlacklistedDevices() {
        return repository.findByBlacklistedTrue();
    }

    /**
     * 获取指纹记录，不存在返回 null。
     *
     * @param fingerprintHash 指纹哈希
     * @return 指纹记录或 null
     */
    public DeviceFingerprint getFingerprint(String fingerprintHash) {
        return repository.findByFingerprintHash(fingerprintHash).orElse(null);
    }

    // --- 内部工具方法 ---

    /**
     * 将新地址追加到 linkedAddresses 字符串中，去重，最大 2048 字符截断。
     */
    private String appendLinkedAddress(String existingLinked, String newAddress) {
        Set<String> addresses = new LinkedHashSet<>();
        if (existingLinked != null && !existingLinked.isEmpty()) {
            addresses.addAll(Arrays.asList(existingLinked.split(",")));
        }
        addresses.add(newAddress);

        String result = String.join(",", addresses);
        if (result.length() > MAX_LINKED_ADDRESSES_LENGTH) {
            result = result.substring(0, MAX_LINKED_ADDRESSES_LENGTH);
        }
        return result;
    }

    /**
     * 统计设备指纹关联的不同地址数量（payerAddress + linkedAddresses 中的地址）。
     */
    private int countLinkedAddresses(DeviceFingerprint fp) {
        Set<String> addresses = new LinkedHashSet<>();
        if (fp.getPayerAddress() != null && !fp.getPayerAddress().isEmpty()) {
            addresses.add(fp.getPayerAddress());
        }
        if (fp.getLinkedAddresses() != null && !fp.getLinkedAddresses().isEmpty()) {
            addresses.addAll(Arrays.asList(fp.getLinkedAddresses().split(",")));
        }
        return addresses.size();
    }

    /**
     * null 转空字符串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 字节数组转 hex 字符串。
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}