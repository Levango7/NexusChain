package org.nexus.gateway.security.encryption;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.audit.SecurityMetricsRecorder;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EncryptionService} 对 {@link SecurityMetricsRecorder} 两个加密 meter 的接线回归。
 *
 * <p><b>为什么需要这个测试</b>：2026-11-08 之前这两个 meter 是「注册了没人喂」——
 * {@code /actuator/prometheus} 能看到序列、值恒为 0，面板看起来是通的。
 * 本仓已犯过三次同类缺陷（{@code nexus_3ds_auth_total}、
 * {@code PaymentMetrics} 全部 7 个、本类这两个），所以接线必须由测试钉住。</p>
 *
 * <p><b>断言口径</b>：一律用 {@link PrometheusMeterRegistry#scrape()}（**导出名**）
 * 而不是 {@code registry.get(...)}（吃的是注册名）。两者在 Micrometer 里不是一回事，
 * 历史上有面板查错名字的实例（{@code nexus_orders_created_total} 实际导出为
 * {@code nexus_orders_total}）。</p>
 */
@DisplayName("EncryptionService 业务指标接线")
class EncryptionMetricsWiringTest {

    private static final String COUNTER = "nexus_encryption_operations_total";
    private static final String TIMER = "nexus_encryption_latency_seconds";

    private PrometheusMeterRegistry registry;
    private EncryptionService service;
    private byte[] dek;

    @BeforeEach
    void setUp() throws Exception {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SecurityMetricsRecorder recorder = new SecurityMetricsRecorder(registry);

        service = new EncryptionService();
        service.setSecurityMetricsRecorder(recorder);

        dek = new byte[32];
        new SecureRandom().nextBytes(dek);
    }

    private double counterCount() {
        return registry.get(COUNTER).counter().count();
    }

    private double timerCount() {
        return registry.get(TIMER).timer().count();
    }

    @Test
    @DisplayName("导出的 Prometheus 名字与登记名一致（不出现 _total_total / _seconds_seconds）")
    void exportsDocumentedPrometheusNames() {
        String text = registry.scrape();

        // 两个 meter 的注册名本身就带了 _total / _seconds 后缀；
        // Micrometer 的命名约定遇到已有后缀不会重复追加，这里把它钉死。
        assertTrue(text.contains(COUNTER + " "),
                "未导出 " + COUNTER + "；实际抓取文本：\n" + text);
        assertTrue(text.contains(TIMER + "_count"),
                "未导出 " + TIMER + "_count；实际抓取文本：\n" + text);

        assertFalse(text.contains(COUNTER + "_total"),
                "出现 " + COUNTER + "_total（后缀被重复追加），面板按 " + COUNTER + " 查会查不到：\n" + text);
        assertFalse(text.contains(TIMER + "_seconds"),
                "出现 " + TIMER + "_seconds（后缀被重复追加），面板按 " + TIMER + " 查会查不到：\n" + text);
    }

    @Test
    @DisplayName("encrypt 计一次操作并记录一次延迟样本")
    void encryptCountsOperationAndLatency() {
        assertNotNull(service.encrypt("6222020000000000", dek));

        assertEquals(1.0, counterCount(), "encrypt 后计数器应为 1");
        assertEquals(1.0, timerCount(), "encrypt 后延迟样本应为 1");
    }

    @Test
    @DisplayName("decrypt 计一次操作（与 encrypt 共用同一计数器）")
    void decryptCountsOperation() {
        EncryptedField field = service.encrypt("6222020000000000", dek);
        assertEquals(1.0, counterCount());

        assertEquals("6222020000000000", service.decrypt(field.getCiphertext(), field.getIv(), dek));
        assertEquals(2.0, counterCount(), "decrypt 后再加 1");
        assertEquals(2.0, timerCount());
    }

    @Test
    @DisplayName("decryptFromBase64 委托 decrypt，不得重复计数")
    void decryptFromBase64CountsExactlyOnce() {
        EncryptedField field = service.encrypt("6222020000000000", dek);
        assertEquals(1.0, counterCount());

        assertEquals("6222020000000000",
                service.decryptFromBase64(field.getCiphertextBase64(), field.getIvBase64(), dek));

        // 若在 decryptFromBase64 里也计一次，这里会是 3.0
        assertEquals(2.0, counterCount(),
                "decryptFromBase64 只能计 1 次（它内部调 decrypt）；出现 3.0 说明重复计数");
    }

    @Test
    @DisplayName("解密失败也要计数（计数器语义是「操作总数」，不是「成功数」）")
    void failedDecryptStillCounts() {
        EncryptedField field = service.encrypt("6222020000000000", dek);
        assertEquals(1.0, counterCount());

        byte[] tampered = field.getCiphertext().clone();
        tampered[0] ^= 0x01; // 破坏 GCM 认证标签 → DATA_INTEGRITY_VIOLATION

        boolean threw = false;
        try {
            service.decrypt(tampered, field.getIv(), dek);
        } catch (EncryptionException expected) {
            threw = true;
        }

        assertTrue(threw, "篡改密文必须 fail-closed 抛 EncryptionException");
        assertEquals(2.0, counterCount(), "失败尝试同样计入操作总数");
    }

    @Test
    @DisplayName("未注入 recorder 时（纯单测场景）不抛 NPE，功能不受影响")
    void worksWithoutRecorder() throws Exception {
        EncryptionService bare = new EncryptionService();
        EncryptedField field = bare.encrypt("6222020000000000", dek);

        assertEquals("6222020000000000", bare.decrypt(field.getCiphertext(), field.getIv(), dek));
    }
}
