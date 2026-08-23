package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StakingServiceImpl} 线程安全测试（B-07/B-08/B-09 修复专项）。
 *
 * <p>验证 P0 修复：</p>
 * <ul>
 *   <li>B-07：unstake 使用锁保护"检查余额 + 执行提取"原子操作，防止 TOCTOU 超额提取</li>
 *   <li>B-08：使用 CopyOnWriteArrayList 替代 ArrayList，保证并发修改安全</li>
 *   <li>B-09：使用锁保护 stake/withdraw/distributeRewards 中 Validator 更新的原子性</li>
 * </ul>
 *
 * <p>测试策略：高并发场景下多线程同时 stake/unstake/withdraw，
 * 断言最终状态一致、余额非负、无异常丢失。</p>
 */
class StakingServiceImplConcurrencyTest {

    private ValidatorRegistry registry;
    private StakingServiceImpl staking;

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl(60L, new BigDecimal("0.05"));
        injectField(staking, "validatorRegistry", registry);
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== B-07: 并发 unstake 不超额提取 ====================

    @Test
    @DisplayName("should_notOverdraw_when_concurrentUnstakeExceedsBalance")
    void should_notOverdraw_when_concurrentUnstakeExceedsBalance() throws InterruptedException {
        // 准备：单验证人质押 1000，10 个线程并发各 unstake 200（总 2000 > 1000）
        final String validator = "addr-concurrent";
        registry.register(validator, "pub", new BigDecimal("1000"), 0.05);
        staking.stake(validator, new BigDecimal("1000"));
        assertThat(staking.getStake(validator)).isEqualByComparingTo(new BigDecimal("1000"));

        int threadCount = 10;
        BigDecimal unstakeAmount = new BigDecimal("200");

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    staking.unstake(validator, unstakeAmount);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    // 余额不足是预期行为
                    failureCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(terminated).as("线程池应在 10s 内终止").isTrue();
        assertThat(errors).as("不应有非预期异常: " + errors).isEmpty();

        // 关键断言：最多 5 个线程成功（1000 / 200 = 5），其余失败
        assertThat(successCount.get()).as("成功 unstake 次数应 <= 5").isLessThanOrEqualTo(5);
        assertThat(successCount.get() + failureCount.get()).isEqualTo(threadCount);

        // 余额不应为负
        BigDecimal finalStake = staking.getStake(validator);
        assertThat(finalStake.compareTo(BigDecimal.ZERO))
                .as("最终质押余额不应为负: " + finalStake)
                .isGreaterThanOrEqualTo(0);

        // 余额应为 1000 - 200 * successCount
        BigDecimal expected = new BigDecimal("1000")
                .subtract(unstakeAmount.multiply(BigDecimal.valueOf(successCount.get())));
        assertThat(finalStake).isEqualByComparingTo(expected);

        // Validator 上的 stakeAmount 也应一致（B-09 修复）
        Validator v = registry.getValidator(validator);
        assertThat(v.getStakeAmount())
                .as("Validator stakeAmount 应与 stakes 一致")
                .isEqualByComparingTo(finalStake.add(new BigDecimal("1000"))); // 初始 register 1000 + stake 1000 - unstake
        // 注：register 设置 stakeAmount=1000，stake 又加 1000 = 2000，unstake 每次 -200
        // 所以 Validator.stakeAmount = 2000 - 200 * successCount
        BigDecimal expectedValidatorStake = new BigDecimal("2000")
                .subtract(unstakeAmount.multiply(BigDecimal.valueOf(successCount.get())));
        assertThat(v.getStakeAmount()).isEqualByComparingTo(expectedValidatorStake);
    }

    // ==================== B-07: 并发 unstake + withdraw 不导致余额为负 ====================

    @Test
    @DisplayName("should_keepNonNegativeBalance_when_concurrentUnstakeAndWithdraw")
    void should_keepNonNegativeBalance_when_concurrentUnstakeAndWithdraw() throws InterruptedException {
        final String validator = "addr-mixed";
        staking.stake(validator, new BigDecimal("1000"));

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // 一半线程 unstake 100，一半线程 withdraw
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    if (idx % 2 == 0) {
                        staking.unstake(validator, new BigDecimal("100"));
                    } else {
                        staking.withdraw(validator);
                    }
                } catch (IllegalArgumentException e) {
                    // 余额不足是预期
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(errors).as("不应有非预期异常: " + errors).isEmpty();

        // 余额不应为负
        BigDecimal finalStake = staking.getStake(validator);
        assertThat(finalStake.compareTo(BigDecimal.ZERO))
                .as("最终质押余额不应为负: " + finalStake)
                .isGreaterThanOrEqualTo(0);
    }

    // ==================== B-09: 并发 stake 保持一致性 ====================

    @Test
    @DisplayName("should_keepConsistency_when_concurrentStake")
    void should_keepConsistency_when_concurrentStake() throws InterruptedException {
        final String validator = "addr-stake";
        registry.register(validator, "pub", new BigDecimal("100"), 0.05);

        int threadCount = 10;
        BigDecimal stakeAmount = new BigDecimal("100");

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    staking.stake(validator, stakeAmount);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(terminated).as("线程池应在 10s 内终止").isTrue();
        assertThat(errors).as("不应有异常: " + errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // 最终质押应为 100 * 10 = 1000
        BigDecimal expected = stakeAmount.multiply(BigDecimal.valueOf(threadCount));
        assertThat(staking.getStake(validator)).isEqualByComparingTo(expected);

        // Validator.stakeAmount 应一致：初始 100 + 1000 = 1100
        Validator v = registry.getValidator(validator);
        assertThat(v.getStakeAmount())
                .as("Validator.stakeAmount 应与 stakes 一致（B-09 修复）")
                .isEqualByComparingTo(new BigDecimal("100").add(expected));
    }

    // ==================== B-07: 单线程超额 unstake 抛异常 ====================

    @Test
    @DisplayName("should_throw_when_unstakeExceedsBalance")
    void should_throw_when_unstakeExceedsBalance() {
        staking.stake("addr1", new BigDecimal("100"));

        assertThatThrownBy(() -> staking.unstake("addr1", new BigDecimal("101")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");

        // 余额仍为 100
        assertThat(staking.getStake("addr1")).isEqualByComparingTo(new BigDecimal("100"));
    }

    // ==================== B-08: 并发 unstake 不抛 ConcurrentModificationException ====================

    @Test
    @DisplayName("should_notThrowConcurrentModification_when_concurrentUnstakeAndWithdraw")
    void should_notThrowConcurrentModification_when_concurrentUnstakeAndWithdraw() throws InterruptedException {
        // B-08 修复：CopyOnWriteArrayList 保证并发修改不抛 ConcurrentModificationException
        final String validator = "addr-cme";
        staking.stake(validator, new BigDecimal("10000"));

        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    // 不同线程交替 unstake / withdraw / getWithdrawable
                    if (idx % 3 == 0) {
                        staking.unstake(validator, new BigDecimal("10"));
                    } else if (idx % 3 == 1) {
                        staking.withdraw(validator);
                    } else {
                        staking.getWithdrawable(validator);
                    }
                } catch (IllegalArgumentException e) {
                    // 余额不足是预期
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        done.await(15, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 关键断言：不应有 ConcurrentModificationException
        for (Throwable t : errors) {
            assertThat(t).as("不应抛 ConcurrentModificationException: " + t)
                    .isNotInstanceOf(java.util.ConcurrentModificationException.class);
        }

        // 余额非负
        BigDecimal finalStake = staking.getStake(validator);
        assertThat(finalStake.compareTo(BigDecimal.ZERO))
                .as("最终余额不应为负: " + finalStake)
                .isGreaterThanOrEqualTo(0);
    }

    // ==================== B-09: distributeRewards 并发安全 ====================

    @Test
    @DisplayName("should_beSafe_when_concurrentDistributeRewards")
    void should_beSafe_when_concurrentDistributeRewards() throws InterruptedException {
        registry.register("addr1", "pub1", new BigDecimal("1000"), 0.05);
        staking.stake("addr1", new BigDecimal("1000"));

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    staking.distributeRewards();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(errors).as("并发 distributeRewards 不应抛异常: " + errors).isEmpty();

        // 余额应大于初始 1000（奖励已分发）
        assertThat(staking.getStake("addr1").compareTo(new BigDecimal("1000")))
                .as("奖励分发后余额应增加").isGreaterThan(0);
    }

    // ==================== 边界：并发 unstake 金额恰好等于余额 ====================

    @Test
    @DisplayName("should_allowOnlyOneSucceed_when_concurrentUnstakeExactBalance")
    void should_allowOnlyOneSucceed_when_concurrentUnstakeExactBalance() throws InterruptedException {
        // 余额 100，2 个线程各 unstake 100，应只有 1 个成功
        final String validator = "addr-exact";
        staking.stake(validator, new BigDecimal("100"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    staking.unstake(validator, new BigDecimal("100"));
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                }
                return null;
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // 恰好一个成功，一个失败
        assertThat(successCount.get()).as("应恰好一个成功").isEqualTo(1);
        assertThat(failureCount.get()).as("应恰好一个失败").isEqualTo(1);
        // 余额为 0
        assertThat(staking.getStake(validator)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}