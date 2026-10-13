package org.nexus.gateway.reserve;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 备付金自动补充的多副本防护探针（缺陷探针转正的回归用例，2026-10-13）。
 *
 * <p><b>这是活路径</b>（区别于同批的结算划拨那条死路径）：
 * {@code ReserveMonitorScheduler:39} 的 cron 默认每 10 分钟一次，
 * 开关 {@code nexus.reserve-monitor.enabled} 默认 true，且 values-prod.yaml 里没有覆盖；
 * 补充动作落到 {@code ReserveFundService:224} 的真实
 * {@code accountService.transfer(...)}。</p>
 *
 * <p><b>缺陷形态</b>：触发判据只有"余额 &lt; 阈值"（{@code ReserveFundService:203}），
 * <b>没有任何认领、幂等键或"补充中"标记</b>。prod 是
 * {@code values-prod.yaml:20-21} 的 {@code nexus-gateway.replicaCount: 3}，
 * 而 {@code monitor()} 上没有 {@code @SchedulerLock} ⇒ 三个副本在同一个整十分钟
 * 各自读到"余额不足"，各自转一笔 ⇒ 备付金最多被超额补足 3 倍。
 * 范围限定（不夸大）：只有 {@code autoReplenishEnabled=true} 的商户会被扫
 * （{@code ReserveConfig} 实体默认 false，逐商户 opt-in），
 * 且这是"同一 tick 最多 3 倍"，不是无界重复 —— 补足后余额达标，下一个 tick 就停。</p>
 *
 * <p><b>为什么 @Version 救不了这里</b>：{@code MerchantAccount} 的乐观锁只保证余额算术一致
 * （谁后提交谁基于新余额算），它压根不是"同一笔补充只做一次"的判据 —— 三次转账在业务上
 * 各自都是合法的。所以 {@link #merchantAccountVersionIsNotADeduplicationGuard()}
 * 把这点写明：有 {@code @Version} ≠ 幂等。</p>
 *
 * <p><b>锁为什么是恰到好处的修法</b>：判据是"余额"这种共享 DB 状态，一旦补足成功就会
 * 自己把条件抹掉（自终止），所以不存在"跨 tick 反复执行"的第二重风险，
 * 不需要像划拨指令那样再加 CAS 认领。{@link #replenishIsSelfTerminatingOnceBalanceMeetsThreshold()}
 * 就是把"自终止"这个承重性质钉住：余额回到阈值以上后，绝不能再转出第二笔。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReserveAutoReplenishSingleFlightTest {

    @Mock
    private ReserveConfigRepository configRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private MerchantAccountRepository accountRepository;

    private ReserveFundService fundService;
    private ReserveMonitorScheduler scheduler;

    private static final long MERCHANT_ID = 9L;
    /** 阈值取 600 而补充额取 500：让"补一次就达标"在这组数字下成立（见下面用例的注释）。 */
    private static final BigDecimal THRESHOLD = new BigDecimal("600.00");
    private static final BigDecimal REPLENISH_AMOUNT = new BigDecimal("500.00");

    private static ReserveConfig autoConfig() {
        ReserveConfig config = new ReserveConfig();
        config.setId(1L);
        config.setMerchantId(MERCHANT_ID);
        config.setAutoReplenishEnabled(true);
        config.setReplenishThreshold(THRESHOLD);
        config.setReplenishAmount(REPLENISH_AMOUNT);
        config.setReplenishSourceAccountType(AccountType.BALANCE);
        return config;
    }

    private static MerchantAccount account(String accountId, String balance) {
        MerchantAccount account = new MerchantAccount();
        account.setAccountId(accountId);
        account.setMerchantId(MERCHANT_ID);
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    @BeforeEach
    void setUp() {
        fundService = new ReserveFundService(configRepository, accountService, accountRepository);
        scheduler = new ReserveMonitorScheduler(fundService, true);
    }

    @Test
    @DisplayName("监控入口必须挂 @SchedulerLock（三个副本各补一笔 = 3 倍超额补足）")
    void monitorMustCarrySchedulerLock() throws NoSuchMethodException {
        Method method = ReserveMonitorScheduler.class.getMethod("monitor");

        assertTrue(method.isAnnotationPresent(SchedulerLock.class),
                "ReserveMonitorScheduler.monitor 缺 @SchedulerLock：cron 默认每 10 分钟、"
                        + "nexus.reserve-monitor.enabled 默认 true 且 prod values 未覆盖，"
                        + "replicaCount=3 ⇒ 三个副本同刻都读到「余额<阈值」并各自 accountService.transfer，"
                        + "备付金被超额补足最多 3 倍");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertFalse(lock.lockAtMostFor().isEmpty(),
                "必须显式 lockAtMostFor：副本崩溃后锁要自动释放，否则备付金补足永久停摆");
        assertFalse(lock.lockAtLeastFor().isEmpty(),
                "必须显式 lockAtLeastFor：防止快速重启的副本在同一分钟里重复补足");
    }

    @Test
    @DisplayName("自终止性质 — 补足后余额达标，不得再转出第二笔")
    void replenishIsSelfTerminatingOnceBalanceMeetsThreshold() {
        when(configRepository.findByAutoReplenishEnabled(true)).thenReturn(List.of(autoConfig()));
        // 第一次读余额不足；第二次读已补足到位（真库里就是这种演进）
        // 顺带记一条配置事实：补足额(500) 小于阈值(600) 时"补一次"是达不了标的，
        // 要连续几个 tick 补到余额 >= 阈值才停 —— 所以"跨 tick 再次转账"本身是合法行为，
        // 唯一能防的就是"同一 tick 三个副本各自读到同一个低余额"，也就是这把锁。
        when(accountRepository.findByMerchantIdAndAccountType(MERCHANT_ID, AccountType.RESERVE))
                .thenReturn(Optional.of(account("ACC-RESERVE", "200.00")),
                        Optional.of(account("ACC-RESERVE", "700.00")));
        // 取账户走 accountService（它是 mock，必须显式打桩，否则拿到 null 被 autoReplenish 的
        // try/catch 吞成 WARN —— 那会让红点变成"夹具坏了"而不是"性质不成立"）
        when(accountService.getOrCreateAccount(MERCHANT_ID, AccountType.BALANCE))
                .thenReturn(account("ACC-BALANCE", "9000.00"));
        when(accountService.getOrCreateAccount(MERCHANT_ID, AccountType.RESERVE))
                .thenReturn(account("ACC-RESERVE", "200.00"));

        fundService.autoReplenish();
        fundService.autoReplenish();

        verify(accountService, times(1))
                .transfer(eq("ACC-BALANCE"), eq("ACC-RESERVE"), eq(REPLENISH_AMOUNT));
    }

    @Test
    @DisplayName("源账户余额不足时必须放弃，不得转出")
    void insufficientSourceBalanceMustNotTransfer() {
        when(configRepository.findByAutoReplenishEnabled(true)).thenReturn(List.of(autoConfig()));
        when(accountRepository.findByMerchantIdAndAccountType(MERCHANT_ID, AccountType.RESERVE))
                .thenReturn(Optional.of(account("ACC-RESERVE", "200.00")));
        when(accountService.getOrCreateAccount(MERCHANT_ID, AccountType.BALANCE))
                .thenReturn(account("ACC-BALANCE", "100.00"));
        when(accountService.getOrCreateAccount(MERCHANT_ID, AccountType.RESERVE))
                .thenReturn(account("ACC-RESERVE", "200.00"));

        int replenished = fundService.autoReplenish();

        assertEquals(0, replenished, "源账户只有 100.00，补 500.00 必须整笔放弃");
        verify(accountService, times(0)).transfer(any(), any(), any());
    }

    @Test
    @DisplayName("有 @Version ≠ 幂等 — 乐观锁只保余额算术，不保「同一笔补充只做一次」")
    void merchantAccountVersionIsNotADeduplicationGuard() {
        assertTrue(hasVersionField(MerchantAccount.class),
                "MerchantAccount 的 @Version 是余额算术的防线，留着它；"
                        + "但别把它当去重 —— 三次合法转账各自都能基于最新余额提交成功");

        // 这条用例的价值在于"命名事实"：读代码的人容易以为有乐观锁就幂等。
        // 真正的去重只能来自 @SchedulerLock（本 PR）或幂等键（PR 里列为后续建议）。
    }

    private static boolean hasVersionField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(jakarta.persistence.Version.class)) {
                return true;
            }
        }
        return false;
    }
}
