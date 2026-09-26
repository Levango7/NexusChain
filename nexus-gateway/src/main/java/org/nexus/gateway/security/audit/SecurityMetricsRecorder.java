package org.nexus.gateway.security.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全指标记录服务。基于 Micrometer {@link MeterRegistry} 定义 Prometheus 指标，
 * 暴露加密操作、防重放拦截、3DS 认证、密码验证、密钥轮换等安全维度的监控数据。
 *
 * <p>指标命名遵循 nexus_ 前缀规范，与项目现有 {@code PaymentMetrics} 的
 * nexus_ 前缀保持一致。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §9.1 — Prometheus 指标定义。
 * 经验来源：2026-09-17-spring-boot-actuator-micrometer-observability-setup —
 * 使用 @Component/@Service 持有 MeterRegistry，提供 recordXxx() 方法供其他组件调用。</p>
 */
@Service
public class SecurityMetricsRecorder {

    private final Counter encryptionOperationsTotal;
    private final Timer encryptionLatencySeconds;
    private final Counter replayInterceptionsTotal;
    private final Counter threeDsAuthTotal;
    private final Timer threeDsAuthLatencySeconds;
    private final Counter passwordValidationsTotal;
    private final Counter passwordLocksTotal;
    private final AtomicInteger kekVersionValue;
    private final AtomicInteger keyRotationProgressValue;

    public SecurityMetricsRecorder(MeterRegistry meterRegistry) {
        // 加密操作计数器
        this.encryptionOperationsTotal = Counter.builder("nexus_encryption_operations_total")
                .description("Total encryption/decryption operations")
                .tag("operation", "all")
                .register(meterRegistry);

        // 加密操作延迟计时器
        this.encryptionLatencySeconds = Timer.builder("nexus_encryption_latency_seconds")
                .description("Encryption/decryption operation latency")
                .register(meterRegistry);

        // 防重放拦截计数器
        this.replayInterceptionsTotal = Counter.builder("nexus_replay_interceptions_total")
                .description("Total replay attack interceptions")
                .register(meterRegistry);

        // 3DS 认证计数器
        this.threeDsAuthTotal = Counter.builder("nexus_3ds_auth_total")
                .description("Total 3DS authentication operations")
                .register(meterRegistry);

        // 3DS 认证延迟计时器
        this.threeDsAuthLatencySeconds = Timer.builder("nexus_3ds_auth_latency_seconds")
                .description("3DS authentication latency")
                .register(meterRegistry);

        // 密码验证计数器
        this.passwordValidationsTotal = Counter.builder("nexus_password_validations_total")
                .description("Total payment password validations")
                .register(meterRegistry);

        // 密码锁定计数器
        this.passwordLocksTotal = Counter.builder("nexus_password_locks_total")
                .description("Total payment password lock events")
                .register(meterRegistry);

        // KEK 版本号 Gauge
        this.kekVersionValue = new AtomicInteger(0);
        Gauge.builder("nexus_kek_version", kekVersionValue, AtomicInteger::doubleValue)
                .description("Current KEK version number")
                .register(meterRegistry);

        // 密钥轮换进度 Gauge
        this.keyRotationProgressValue = new AtomicInteger(0);
        Gauge.builder("nexus_key_rotation_progress", keyRotationProgressValue, AtomicInteger::doubleValue)
                .description("Key rotation migration progress (percentage 0-100)")
                .register(meterRegistry);
    }

    /**
     * 记录加密操作。每次加密/解密操作调用一次。
     */
    public void recordEncryptionOperation() {
        encryptionOperationsTotal.increment();
    }

    /**
     * 获取加密操作延迟计时器，供业务代码记录操作耗时。
     */
    public Timer getEncryptionLatencyTimer() {
        return encryptionLatencySeconds;
    }

    /**
     * 记录防重放拦截。每次检测到重放攻击时调用。
     */
    public void recordReplayInterception() {
        replayInterceptionsTotal.increment();
    }

    /**
     * 记录 3DS 认证操作。每次 3DS 认证流程结束时调用。
     */
    public void recordThreeDsAuth() {
        threeDsAuthTotal.increment();
    }

    /**
     * 获取 3DS 认证延迟计时器，供业务代码记录认证耗时。
     */
    public Timer getThreeDsAuthLatencyTimer() {
        return threeDsAuthLatencySeconds;
    }

    /**
     * 记录密码验证操作。每次支付密码验证时调用。
     */
    public void recordPasswordValidation() {
        passwordValidationsTotal.increment();
    }

    /**
     * 记录密码锁定事件。当商户密码连续失败达到阈值被锁定时调用。
     */
    public void recordPasswordLock() {
        passwordLocksTotal.increment();
    }

    /**
     * 更新当前 KEK 版本号。
     */
    public void updateKekVersion(int version) {
        kekVersionValue.set(version);
    }

    /**
     * 更新密钥轮换迁移进度（0-100 百分比）。
     */
    public void updateKeyRotationProgress(int progress) {
        keyRotationProgressValue.set(progress);
    }
}