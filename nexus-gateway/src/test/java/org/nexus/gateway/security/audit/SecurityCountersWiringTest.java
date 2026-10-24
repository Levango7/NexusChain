package org.nexus.gateway.security.audit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.encryption.KeyRotationScheduler;
import org.nexus.gateway.security.password.MerchantPaymentPassword;
import org.nexus.gateway.security.password.MerchantPaymentPasswordRepository;
import org.nexus.gateway.security.password.PasswordHistoryService;
import org.nexus.gateway.security.password.PasswordSecurityConfig;
import org.nexus.gateway.security.password.PasswordSecurityConfigService;
import org.nexus.gateway.security.password.PasswordStatus;
import org.nexus.gateway.security.password.PaymentPasswordService;
import org.nexus.gateway.security.threeds.AuthStatus;
import org.nexus.gateway.security.threeds.ThreeDsAuthCompletedEvent;
import org.nexus.gateway.security.threeds.ThreeDsAuthCompletedObserver;
import org.nexus.gateway.security.threeds.TransStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 台账 #52：把「指标注册了却没人喂」这一类空转变成可证伪的断言。
 *
 * <p>这批指标全部在 {@link SecurityMetricsRecorder} 构造器里注册过，但注册成功不等于有人写它：
 * 没被喂过的计数器在 Prometheus 上是一条恒 0 的序列——比干脆没有这条线更坏，因为 0 会被读成
 * 事实，缺线至少会被读成"没接"。所以本类的判据一律是<b>跑一条真实业务路径之后再读注册表</b>，
 * 而不是断"recorder 里有这个方法"。</p>
 *
 * <p>本类覆盖密码两个计数器与 3DS 延迟直方图；KEK 两个 Gauge 在
 * {@code KeyRotationMetricsWiringTest}（那边要直接调包私有的百分比函数）。</p>
 *
 * <p>装配走 Spring 那条构造器：用真容器取 {@link ObjectProvider}，"容器里有 bean"与
 * "容器里没有 bean"两种形态都由容器自己给出，不手搓假 provider。</p>
 */
class SecurityCountersWiringTest {

    private static final String VALID_PASSWORD = "Str0ng@Pass";
    private static final String WRONG_PASSWORD = "Wr0ng@Pass";
    private static final Long MERCHANT_ID = 1001L;
    private static final String TENANT_ID = "tenant-001";

    private final List<AnnotationConfigApplicationContext> opened = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        opened.forEach(AnnotationConfigApplicationContext::close);
        opened.clear();
    }

    // ==================== nexus_password_validations_total ====================

    @Test
    @DisplayName("真的做了 bcrypt 比对才算一次验证；四种提前返回不计数")
    void validationCounterCountsOnlyRealComparisons() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        MerchantPaymentPasswordRepository repository = mock(MerchantPaymentPasswordRepository.class);
        PasswordSecurityConfigService configService = mock(PasswordSecurityConfigService.class);
        when(configService.getConfig(TENANT_ID)).thenReturn(passwordConfig(5));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentPasswordService service = passwordService(registry, repository, configService);

        // ① 未设置密码 → SKIPPED（没进比对）
        when(repository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.SKIPPED,
                service.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID).getStatus());
        assertEquals(0.0, counter(registry, "nexus_password_validations_total"),
                "没做比对的一次调用不该计入验证数");

        // ② 正确密码 → 计 1
        givenStored(encoder.encode(VALID_PASSWORD), 0, null, repository);
        service.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);
        assertEquals(1.0, counter(registry, "nexus_password_validations_total"));

        // ③ 错误密码 → 再计 1（成功与失败都要计，否则「验证次数」只剩成功侧）
        givenStored(encoder.encode(VALID_PASSWORD), 0, null, repository);
        service.verifyPassword(MERCHANT_ID, WRONG_PASSWORD, TENANT_ID);
        assertEquals(2.0, counter(registry, "nexus_password_validations_total"));

        // ④ 仍在锁定期 → 不计数
        givenStored(encoder.encode(VALID_PASSWORD), 0, Instant.now().plus(10, ChronoUnit.MINUTES), repository);
        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.LOCKED,
                service.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID).getStatus());

        // ⑤ 密码已过期 → 不计数
        MerchantPaymentPassword expired = activeEntity(encoder.encode(VALID_PASSWORD));
        expired.setLastChangedAt(Instant.now().minus(200, ChronoUnit.DAYS));
        when(repository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(expired));
        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.EXPIRED,
                service.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID).getStatus());

        assertEquals(2.0, counter(registry, "nexus_password_validations_total"),
                "锁定中与过期这两条路径没做比对，不该把验证数推高");
    }

    // ==================== nexus_password_locks_total ====================

    @Test
    @DisplayName("跨过阈值那一次计一次锁定；未跨阈值的失败不计")
    void lockCounterFiresOnlyAtThresholdCrossing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        MerchantPaymentPasswordRepository repository = mock(MerchantPaymentPasswordRepository.class);
        PasswordSecurityConfigService configService = mock(PasswordSecurityConfigService.class);
        when(configService.getConfig(TENANT_ID)).thenReturn(passwordConfig(2));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentPasswordService service = passwordService(registry, repository, configService);

        // 第 1 次失败：未达阈值 → 只涨验证数
        givenStored(encoder.encode(VALID_PASSWORD), 0, null, repository);
        service.verifyPassword(MERCHANT_ID, WRONG_PASSWORD, TENANT_ID);
        assertEquals(0.0, counter(registry, "nexus_password_locks_total"),
                "失败一次不等于锁定，否则锁数会高估受害账户数");

        // 第 2 次失败：跨阈值 → 计 1，与审计同一时点
        givenStored(encoder.encode(VALID_PASSWORD), 1, null, repository);
        service.verifyPassword(MERCHANT_ID, WRONG_PASSWORD, TENANT_ID);
        assertEquals(1.0, counter(registry, "nexus_password_locks_total"));
    }

    // ==================== nexus_3ds_auth_latency_seconds ====================

    @Test
    @DisplayName("两个时刻都在 ⇒ 记一次真实延迟")
    void latencySampledWhenBothTimestampsPresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreeDsAuthCompletedObserver observer = observer(registry);

        observer.onThreeDsAuthCompleted(event(
                LocalDateTime.of(2026, 10, 24, 10, 0, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0, 4)));

        assertEquals(1.0, timerCount(registry));
        assertEquals(4.0, timerSeconds(registry), 0.001,
                "采到的必须是真实跨度，不是 0");
    }

    @Test
    @DisplayName("完成或发起时刻缺失 ⇒ 跳过采样，绝不记 0 秒；计数照常")
    void latencySkippedWhenAnyTimestampMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreeDsAuthCompletedObserver observer = observer(registry);
        LocalDateTime start = LocalDateTime.of(2026, 10, 24, 10, 0, 0);

        observer.onThreeDsAuthCompleted(event(start, null));
        observer.onThreeDsAuthCompleted(event(null, start));
        observer.onThreeDsAuthCompleted(event(null, null));

        assertEquals(0.0, timerCount(registry),
                "缺失时刻记成 0 秒会把 p50/p95 拉向「3DS 秒过」这一假象");
        assertEquals(3.0, counter(registry, "nexus_3ds_auth_total"),
                "跳过延迟采样不能连带把计数也吞掉：认证确实发生了");
    }

    @Test
    @DisplayName("完成早于发起（脏数据/时钟回拨）⇒ 同样跳过")
    void latencySkippedWhenSpanNegative() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreeDsAuthCompletedObserver observer = observer(registry);

        observer.onThreeDsAuthCompleted(event(
                LocalDateTime.of(2026, 10, 24, 10, 0, 5),
                LocalDateTime.of(2026, 10, 24, 10, 0, 0)));

        assertEquals(0.0, timerCount(registry), "负数延迟没有意义，宁可少一个样本");
    }

    // ==================== 结构：别踩「多构造器漏标 @Autowired」这个坑 ====================

    @Test
    @DisplayName("两个指标消费方各只有一个公开构造器 ⇒ Spring 不可能静默挑到不收指标的那个")
    void metricConsumersHaveSingleConstructor() {
        assertEquals(1, PaymentPasswordService.class.getConstructors().length,
                "一旦出现第二个公开构造器且没标 @Autowired，Spring 会静默选另一个，"
                        + "指标依赖注入不进来而所有单测照样全绿（本仓已第三次踩到）");
        assertEquals(1, KeyRotationScheduler.class.getConstructors().length,
                "同上：新增可选依赖必须加在那个唯一构造器上");
    }

    // ==================== 辅助 ====================

    /** 真容器给出的 provider：注册了 bean 就取得到——不手搓假 provider。 */
    private ObjectProvider<SecurityMetricsRecorder> providerWith(SimpleMeterRegistry registry) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        SecurityMetricsRecorder recorder = new SecurityMetricsRecorder(registry);
        ctx.registerBean(SecurityMetricsRecorder.class, () -> recorder);
        ctx.refresh();
        opened.add(ctx);
        return ctx.getBeanProvider(SecurityMetricsRecorder.class);
    }

    private PaymentPasswordService passwordService(SimpleMeterRegistry registry,
                                                   MerchantPaymentPasswordRepository repository,
                                                   PasswordSecurityConfigService configService) {
        return new PaymentPasswordService(repository, configService,
                mock(PasswordHistoryService.class), mock(SecurityAuditService.class),
                providerWith(registry));
    }

    private ThreeDsAuthCompletedObserver observer(SimpleMeterRegistry registry) {
        return new ThreeDsAuthCompletedObserver(new SecurityMetricsRecorder(registry),
                mock(SecurityAuditService.class));
    }

    private ThreeDsAuthCompletedEvent event(LocalDateTime startedAt, LocalDateTime completedAt) {
        return new ThreeDsAuthCompletedEvent(this, 9001L, MERCHANT_ID, TENANT_ID,
                TransStatus.Y, AuthStatus.COMPLETED, 10, true, false, startedAt, completedAt);
    }

    private void givenStored(String hash, int failedAttempts, Instant lockedUntil,
                             MerchantPaymentPasswordRepository repository) {
        MerchantPaymentPassword entity = activeEntity(hash);
        entity.setFailedAttempts(failedAttempts);
        entity.setLockedUntil(lockedUntil);
        when(repository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
    }

    private PasswordSecurityConfig passwordConfig(int maxFailedAttempts) {
        PasswordSecurityConfig config = new PasswordSecurityConfig();
        config.setTenantId(TENANT_ID);
        config.setMinLength(8);
        config.setRequireUppercase(true);
        config.setRequireLowercase(true);
        config.setRequireDigit(true);
        config.setRequireSpecialChar(true);
        config.setMaxFailedAttempts(maxFailedAttempts);
        config.setLockDurationMinutes(30);
        config.setPasswordExpiryDays(90);
        return config;
    }

    private MerchantPaymentPassword activeEntity(String hash) {
        MerchantPaymentPassword entity = new MerchantPaymentPassword();
        entity.setId(1L);
        entity.setMerchantId(MERCHANT_ID);
        entity.setPasswordHash(hash);
        entity.setStatus(PasswordStatus.ACTIVE);
        entity.setFailedAttempts(0);
        entity.setLastChangedAt(Instant.now());
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private double counter(SimpleMeterRegistry registry, String name) {
        return registry.get(name).counter().count();
    }

    private double timerCount(SimpleMeterRegistry registry) {
        return registry.get("nexus_3ds_auth_latency_seconds").timer().count();
    }

    private double timerSeconds(SimpleMeterRegistry registry) {
        return registry.get("nexus_3ds_auth_latency_seconds").timer()
                .totalTime(TimeUnit.SECONDS);
    }
}
