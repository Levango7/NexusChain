package org.nexus.gateway.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DeviceFingerprintService} 单元测试：覆盖指纹生成、记录/更新、
 * 黑名单管理、风险评估、关联设备查询等核心功能。
 */
@ExtendWith(MockitoExtension.class)
class DeviceFingerprintServiceTest {

    @Mock private DeviceFingerprintRepository repository;

    private DeviceFingerprintService service;

    @BeforeEach
    void setUp() {
        service = new DeviceFingerprintService(repository);
    }

    // === generateFingerprintHash ===

    @Test
    @DisplayName("generateFingerprintHash: 相同参数生成相同哈希")
    void generateFingerprintHash_sameParams_sameHash() {
        String hash1 = service.generateFingerprintHash("Mozilla/5.0", "192.168.1.1", "zh-CN",
                "1920x1080", "Asia/Shanghai", "Win32");
        String hash2 = service.generateFingerprintHash("Mozilla/5.0", "192.168.1.1", "zh-CN",
                "1920x1080", "Asia/Shanghai", "Win32");
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }

    @Test
    @DisplayName("generateFingerprintHash: 不同参数生成不同哈希")
    void generateFingerprintHash_differentParams_differentHash() {
        String hash1 = service.generateFingerprintHash("Mozilla/5.0", "192.168.1.1", "zh-CN",
                "1920x1080", "Asia/Shanghai", "Win32");
        String hash2 = service.generateFingerprintHash("Chrome/100", "192.168.1.2", "en-US",
                "1366x768", "America/New_York", "MacIntel");
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("generateFingerprintHash: null参数按空字符串处理，也能生成哈希")
    void generateFingerprintHash_nullParams_generatesHash() {
        String hash = service.generateFingerprintHash(null, null, null, null, null, null);
        assertNotNull(hash);
        assertEquals(64, hash.length());

        // null 参数与空字符串参数应生成相同哈希
        String hash2 = service.generateFingerprintHash("", "", "", "", "", "");
        assertEquals(hash, hash2);
    }

    // === recordOrUpdate ===

    @Test
    @DisplayName("recordOrUpdate: 新指纹 → 创建记录, transactionCount=1")
    void recordOrUpdate_newFingerprint_createsRecord() {
        String hash = "abc123";
        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.empty());
        when(repository.save(any(DeviceFingerprint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceFingerprint result = service.recordOrUpdate(hash, 100L, "0xPayer1",
                "Mozilla/5.0", "192.168.1.1", "zh-CN", "1920x1080", "Asia/Shanghai", "Win32");

        assertNotNull(result);
        assertEquals(hash, result.getFingerprintHash());
        assertEquals(100L, result.getMerchantId());
        assertEquals("0xPayer1", result.getPayerAddress());
        assertEquals(1, result.getTransactionCount());
        assertEquals(DeviceFingerprint.RiskLevel.LOW, result.getRiskLevel());
        assertFalse(result.isBlacklisted());
        assertNotNull(result.getFirstSeenAt());
        assertNotNull(result.getLastSeenAt());

        ArgumentCaptor<DeviceFingerprint> captor = ArgumentCaptor.forClass(DeviceFingerprint.class);
        verify(repository).save(captor.capture());
        assertEquals(1, captor.getValue().getTransactionCount());
    }

    @Test
    @DisplayName("recordOrUpdate: 已有指纹 → 更新lastSeenAt, transactionCount++")
    void recordOrUpdate_existingFingerprint_updatesRecord() {
        String hash = "abc123";
        DeviceFingerprint existing = new DeviceFingerprint();
        existing.setFingerprintHash(hash);
        existing.setPayerAddress("0xPayer1");
        existing.setTransactionCount(5);
        existing.setFirstSeenAt(LocalDateTime.now().minusDays(1));
        LocalDateTime originalLastSeen = LocalDateTime.now().minusHours(1);
        existing.setLastSeenAt(originalLastSeen);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(existing));
        when(repository.save(any(DeviceFingerprint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceFingerprint result = service.recordOrUpdate(hash, 100L, "0xPayer1",
                "Mozilla/5.0", "192.168.1.1", "zh-CN", "1920x1080", "Asia/Shanghai", "Win32");

        assertEquals(6, result.getTransactionCount());
        assertTrue(result.getLastSeenAt().isAfter(originalLastSeen));
        assertNull(result.getLinkedAddresses()); // 相同 payerAddress，不追加
    }

    @Test
    @DisplayName("recordOrUpdate: 已有指纹+新payerAddress → 追加到linkedAddresses")
    void recordOrUpdate_existingFingerprint_newPayerAddress_appendsLinked() {
        String hash = "abc123";
        DeviceFingerprint existing = new DeviceFingerprint();
        existing.setFingerprintHash(hash);
        existing.setPayerAddress("0xPayer1");
        existing.setTransactionCount(5);
        existing.setFirstSeenAt(LocalDateTime.now().minusDays(1));
        existing.setLastSeenAt(LocalDateTime.now().minusHours(1));

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(existing));
        when(repository.save(any(DeviceFingerprint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceFingerprint result = service.recordOrUpdate(hash, 100L, "0xPayer2",
                "Mozilla/5.0", "192.168.1.1", "zh-CN", "1920x1080", "Asia/Shanghai", "Win32");

        assertEquals("0xPayer2", result.getLinkedAddresses());
        assertEquals(6, result.getTransactionCount());
    }

    @Test
    @DisplayName("recordOrUpdate: 已有指纹+相同payerAddress → 不追加")
    void recordOrUpdate_existingFingerprint_samePayerAddress_noAppend() {
        String hash = "abc123";
        DeviceFingerprint existing = new DeviceFingerprint();
        existing.setFingerprintHash(hash);
        existing.setPayerAddress("0xPayer1");
        existing.setTransactionCount(5);
        existing.setFirstSeenAt(LocalDateTime.now().minusDays(1));
        existing.setLastSeenAt(LocalDateTime.now().minusHours(1));

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(existing));
        when(repository.save(any(DeviceFingerprint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceFingerprint result = service.recordOrUpdate(hash, 100L, "0xPayer1",
                "Mozilla/5.0", "192.168.1.1", "zh-CN", "1920x1080", "Asia/Shanghai", "Win32");

        assertNull(result.getLinkedAddresses());
        assertEquals(6, result.getTransactionCount());
    }

    // === isBlacklisted ===

    @Test
    @DisplayName("isBlacklisted: 黑名单指纹 → true")
    void isBlacklisted_blacklistedFingerprint_returnsTrue() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setBlacklisted(true);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertTrue(service.isBlacklisted(hash));
    }

    @Test
    @DisplayName("isBlacklisted: 非黑名单指纹 → false")
    void isBlacklisted_nonBlacklistedFingerprint_returnsFalse() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setBlacklisted(false);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertFalse(service.isBlacklisted(hash));
    }

    @Test
    @DisplayName("isBlacklisted: 不存在指纹 → false")
    void isBlacklisted_nonExistentFingerprint_returnsFalse() {
        String hash = "abc123";
        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.empty());

        assertFalse(service.isBlacklisted(hash));
    }

    // === blacklist ===

    @Test
    @DisplayName("blacklist: 成功加入黑名单")
    void blacklist_success() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setFingerprintHash(hash);
        fp.setBlacklisted(false);
        fp.setRiskLevel(DeviceFingerprint.RiskLevel.LOW);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));
        when(repository.save(any(DeviceFingerprint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.blacklist(hash, "fraud detected");

        assertTrue(fp.isBlacklisted());
        assertEquals(DeviceFingerprint.RiskLevel.BLACKLISTED, fp.getRiskLevel());
        verify(repository).save(fp);
    }

    // === unblacklist ===

    @Test
    @DisplayName("unblacklist: 成功移除黑名单")
    void unblacklist_success() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setFingerprintHash(hash);
        fp.setBlacklisted(true);
        fp.setRiskLevel(DeviceFingerprint.RiskLevel.BLACKLISTED);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));
        when(repository.save(any(DeviceFingerprint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.unblacklist(hash);

        assertFalse(fp.isBlacklisted());
        assertEquals(DeviceFingerprint.RiskLevel.LOW, fp.getRiskLevel());
        verify(repository).save(fp);
    }

    // === assessRisk ===

    @Test
    @DisplayName("assessRisk: 黑名单 → BLACKLISTED")
    void assessRisk_blacklisted_returnsBlacklisted() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setBlacklisted(true);
        fp.setRiskLevel(DeviceFingerprint.RiskLevel.BLACKLISTED);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertEquals(DeviceFingerprint.RiskLevel.BLACKLISTED, service.assessRisk(hash));
    }

    @Test
    @DisplayName("assessRisk: 关联>3个地址 → HIGH")
    void assessRisk_moreThan3Addresses_returnsHigh() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setPayerAddress("0xPayer1");
        fp.setLinkedAddresses("0xPayer2,0xPayer3,0xPayer4");
        fp.setTransactionCount(10);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertEquals(DeviceFingerprint.RiskLevel.HIGH, service.assessRisk(hash));
    }

    @Test
    @DisplayName("assessRisk: 关联2-3个地址 → MEDIUM")
    void assessRisk_2to3Addresses_returnsMedium() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setPayerAddress("0xPayer1");
        fp.setLinkedAddresses("0xPayer2");
        fp.setTransactionCount(10);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertEquals(DeviceFingerprint.RiskLevel.MEDIUM, service.assessRisk(hash));
    }

    @Test
    @DisplayName("assessRisk: 正常 → LOW")
    void assessRisk_normal_returnsLow() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setPayerAddress("0xPayer1");
        fp.setTransactionCount(5);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertEquals(DeviceFingerprint.RiskLevel.LOW, service.assessRisk(hash));
    }

    @Test
    @DisplayName("assessRisk: 交易次数>100 → HIGH")
    void assessRisk_transactionCountOver100_returnsHigh() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setPayerAddress("0xPayer1");
        fp.setTransactionCount(101);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertEquals(DeviceFingerprint.RiskLevel.HIGH, service.assessRisk(hash));
    }

    @Test
    @DisplayName("assessRisk: 交易次数>50 → MEDIUM")
    void assessRisk_transactionCountOver50_returnsMedium() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setPayerAddress("0xPayer1");
        fp.setTransactionCount(51);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        assertEquals(DeviceFingerprint.RiskLevel.MEDIUM, service.assessRisk(hash));
    }

    @Test
    @DisplayName("assessRisk: 不存在指纹 → LOW")
    void assessRisk_nonExistentFingerprint_returnsLow() {
        String hash = "abc123";
        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.empty());

        assertEquals(DeviceFingerprint.RiskLevel.LOW, service.assessRisk(hash));
    }

    // === getLinkedDevices ===

    @Test
    @DisplayName("getLinkedDevices: 返回关联设备列表")
    void getLinkedDevices_returnsList() {
        String payerAddress = "0xPayer1";
        DeviceFingerprint fp1 = new DeviceFingerprint();
        fp1.setPayerAddress(payerAddress);
        DeviceFingerprint fp2 = new DeviceFingerprint();
        fp2.setPayerAddress(payerAddress);

        when(repository.findByPayerAddress(payerAddress)).thenReturn(List.of(fp1, fp2));

        List<DeviceFingerprint> result = service.getLinkedDevices(payerAddress);

        assertEquals(2, result.size());
        verify(repository).findByPayerAddress(payerAddress);
    }

    // === getBlacklistedDevices ===

    @Test
    @DisplayName("getBlacklistedDevices: 返回黑名单列表")
    void getBlacklistedDevices_returnsList() {
        DeviceFingerprint fp1 = new DeviceFingerprint();
        fp1.setBlacklisted(true);
        DeviceFingerprint fp2 = new DeviceFingerprint();
        fp2.setBlacklisted(true);

        when(repository.findByBlacklistedTrue()).thenReturn(List.of(fp1, fp2));

        List<DeviceFingerprint> result = service.getBlacklistedDevices();

        assertEquals(2, result.size());
        verify(repository).findByBlacklistedTrue();
    }

    // === getFingerprint ===

    @Test
    @DisplayName("getFingerprint: 存在 → 返回记录")
    void getFingerprint_exists_returnsRecord() {
        String hash = "abc123";
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setFingerprintHash(hash);

        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.of(fp));

        DeviceFingerprint result = service.getFingerprint(hash);
        assertNotNull(result);
        assertEquals(hash, result.getFingerprintHash());
    }

    @Test
    @DisplayName("getFingerprint: 不存在 → null")
    void getFingerprint_notExists_returnsNull() {
        String hash = "abc123";
        when(repository.findByFingerprintHash(hash)).thenReturn(Optional.empty());

        assertNull(service.getFingerprint(hash));
    }
}