package org.nexus.gateway.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备指纹 Repository，提供按哈希、支付地址、黑名单、风险等级查询的能力。
 */
@Repository
public interface DeviceFingerprintRepository extends JpaRepository<DeviceFingerprint, Long> {

    Optional<DeviceFingerprint> findByFingerprintHash(String fingerprintHash);

    List<DeviceFingerprint> findByPayerAddress(String payerAddress);

    List<DeviceFingerprint> findByBlacklistedTrue();

    List<DeviceFingerprint> findByRiskLevel(DeviceFingerprint.RiskLevel riskLevel);
}