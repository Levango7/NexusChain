package org.nexus.bridge.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.model.InsuranceFundLedgerEntry;
import org.nexus.bridge.repository.InsuranceFundLedgerRepository;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultInsuranceFund} DB 一致性测试（B-04 修复专项）。
 *
 * <p>验证 P0 修复 B-04 的核心不变量：</p>
 * <ul>
 *   <li>deposit / compensate / withdraw 必须「先写 DB 再更新内存」</li>
 *   <li>DB 写入失败时内存不被更新（避免重启后状态丢失）</li>
 *   <li>compensate 余额不足时抛 IllegalStateException 且不写 DB</li>
 *   <li>构造函数 DB 恢复失败时抛 IllegalStateException（不默认 balance=0）</li>
 * </ul>
 *
 * <p>使用 Mockito Mock {@link InsuranceFundLedgerRepository}，
 * 通过 {@code doThrow} 模拟 DB 写入失败，断言内存余额未变更。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultInsuranceFundDbConsistencyTest {

    @Mock
    private InsuranceFundLedgerRepository ledgerRepository;

    private DefaultInsuranceFund fund;

    @BeforeEach
    void setUp() {
        // 使用 lenient 避免 UnnecessaryStubbingException（部分测试不需要此 stub）
        lenient().when(ledgerRepository.findAll()).thenReturn(Collections.emptyList());
        fund = new DefaultInsuranceFund(ledgerRepository);
    }

    // ==================== B-04: deposit 先写 DB 再更新内存 ====================

    @Test
    @DisplayName("should_notUpdateMemoryBalance_when_depositDbWriteFails")
    void should_notUpdateMemoryBalance_when_depositDbWriteFails() {
        // 模拟 DB save 抛异常
        when(ledgerRepository.save(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // 初始余额为 0
        assertThat(fund.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // deposit 应抛 RuntimeException（DB 写入失败）
        assertThatThrownBy(() -> fund.deposit(new BigDecimal("1000")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection lost");

        // 关键断言：DB 失败后内存余额不应被更新（仍为 0）
        assertThat(fund.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        // 确认 save 被调用过一次（尝试写 DB）
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("should_persistToDbBeforeUpdatingMemory_when_depositSucceeds")
    void should_persistToDbBeforeUpdatingMemory_when_depositSucceeds() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        fund.deposit(new BigDecimal("500"));

        // DB 写入成功后内存余额应更新
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("500"));
        verify(ledgerRepository).save(any());
    }

    // ==================== B-04: compensate 先写 DB 再更新内存 ====================

    @Test
    @DisplayName("should_notUpdateMemoryBalance_when_compensateDbWriteFails")
    void should_notUpdateMemoryBalance_when_compensateDbWriteFails() {
        // 先存入 1000 使余额充足
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("1000"));
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("1000"));

        // 切换 save 行为：模拟 DB 写入失败
        org.mockito.Mockito.reset(ledgerRepository);
        lenient().when(ledgerRepository.findAll()).thenReturn(Collections.emptyList());
        when(ledgerRepository.save(any()))
                .thenThrow(new RuntimeException("DB write timeout"));

        // compensate 应抛 RuntimeException
        assertThatThrownBy(() -> fund.compensate("victim-1", new BigDecimal("300"), "loss"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB write timeout");

        // 关键断言：DB 失败后内存余额仍为 1000（未被扣减）
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("should_throwIllegalState_when_compensateBalanceInsufficient")
    void should_throwIllegalState_when_compensateBalanceInsufficient() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("100"));

        // 余额 100，补偿 200 应抛 IllegalStateException
        assertThatThrownBy(() -> fund.compensate("victim-1", new BigDecimal("200"), "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient insurance fund balance");

        // 余额不足时不应写 DB（在 save 之前已抛异常）
        // 注意：deposit 已调用过 save 一次，compensate 失败不应再调用 save
        // 由于 deposit 调用了 save 一次，总调用次数应为 1
        verify(ledgerRepository, times(1)).save(any());
        // 余额仍为 100
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("should_throwIllegalState_when_compensateExceedsBalanceExactly")
    void should_throwIllegalState_when_compensateExceedsBalanceExactly() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("100"));

        // 补偿金额恰好等于余额也应允许（边界测试：amount == balance 应成功）
        // 修改：amount > balance 才抛异常，amount == balance 应成功
        fund.compensate("victim-1", new BigDecimal("100"), "exact amount");
        assertThat(fund.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 再次补偿 1 应抛异常（余额已为 0）
        assertThatThrownBy(() -> fund.compensate("victim-1", new BigDecimal("1"), "over"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== B-04: withdraw 先写 DB 再更新内存 ====================

    @Test
    @DisplayName("should_notUpdateMemoryBalance_when_withdrawDbWriteFails")
    void should_notUpdateMemoryBalance_when_withdrawDbWriteFails() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("1000"));

        org.mockito.Mockito.reset(ledgerRepository);
        lenient().when(ledgerRepository.findAll()).thenReturn(Collections.emptyList());
        when(ledgerRepository.save(any()))
                .thenThrow(new RuntimeException("DB write fails"));

        assertThatThrownBy(() -> fund.withdraw(new BigDecimal("400"), "approver", "reason"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB write fails");

        // DB 失败后余额仍为 1000
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    // ==================== B-03: 构造函数恢复失败抛异常 ====================

    @Test
    @DisplayName("should_throwIllegalState_when_constructorDbRestoreFails")
    void should_throwIllegalState_when_constructorDbRestoreFails() {
        when(ledgerRepository.findAll())
                .thenThrow(new RuntimeException("DB unavailable at startup"));

        // B-03 修复：构造函数恢复失败时抛 IllegalStateException，拒绝启动
        assertThatThrownBy(() -> new DefaultInsuranceFund(ledgerRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restoration failed")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("should_throwIllegalState_when_constructorDbReturnsNull")
    void should_throwIllegalState_when_constructorDbReturnsNull() {
        // 模拟 findAll 返回 null（异常情况）
        when(ledgerRepository.findAll()).thenReturn(null);

        // 应抛 IllegalStateException（NPE 被包装）
        assertThatThrownBy(() -> new DefaultInsuranceFund(ledgerRepository))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== B-04: 多次 deposit/compensate 一致性 ====================

    @Test
    @DisplayName("should_keepMemoryAndDbConsistent_when_multipleOperationsSucceed")
    void should_keepMemoryAndDbConsistent_when_multipleOperationsSucceed() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        fund.deposit(new BigDecimal("1000"));
        fund.deposit(new BigDecimal("500"));
        fund.compensate("v1", new BigDecimal("300"), "loss-1");
        fund.compensate("v2", new BigDecimal("200"), "loss-2");

        // 内存余额应为 1000 + 500 - 300 - 200 = 1000
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("1000"));
        // DB 应被写入 4 次
        verify(ledgerRepository, times(4)).save(any());
    }

    @Test
    @DisplayName("should_rejectNegativeBalance_when_concurrentCompensateExceedsBalance")
    void should_rejectNegativeBalance_when_concurrentCompensateExceedsBalance() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("100"));

        // 第一次补偿 60 成功
        fund.compensate("v1", new BigDecimal("60"), "loss");
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("40"));

        // 第二次补偿 50 应抛异常（余额 40 < 50）
        assertThatThrownBy(() -> fund.compensate("v2", new BigDecimal("50"), "loss"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient");

        // 余额仍为 40，不会变负
        assertThat(fund.getBalance()).isEqualByComparingTo(new BigDecimal("40"));
    }

    // ==================== 参数校验（不写 DB） ====================

    @Test
    @DisplayName("should_notTouchDb_when_depositWithInvalidAmount")
    void should_notTouchDb_when_depositWithInvalidAmount() {
        assertThatThrownBy(() -> fund.deposit(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fund.deposit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fund.deposit(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);

        // 参数非法时不应调用 save
        verify(ledgerRepository, never()).save(any());
        // 余额仍为 0
        assertThat(fund.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("should_notTouchDb_when_compensateWithInvalidArgs")
    void should_notTouchDb_when_compensateWithInvalidArgs() {
        assertThatThrownBy(() -> fund.compensate(null, BigDecimal.ONE, "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fund.compensate("", BigDecimal.ONE, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fund.compensate("v", null, "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fund.compensate("v", BigDecimal.ZERO, "r"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(ledgerRepository, never()).save(any());
    }
}