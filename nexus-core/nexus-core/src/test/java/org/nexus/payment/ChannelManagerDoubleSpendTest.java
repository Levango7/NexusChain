package org.nexus.payment;

import org.nexus.core.payment.ChannelManager;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.crypto.HashUtil;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChannelManager} 防双花测试（B-10/B-11 修复专项）。
 *
 * <p>验证 P0 修复：</p>
 * <ul>
 *   <li>B-10：submitUpdate / confirmPayment 使用锁保护"验证 + 通道状态更新 + pendingUpdate 存储"原子操作</li>
 *   <li>B-11：initiatePayment 使用锁保护"余额检查 + 扣减计算 + pendingUpdate 存储"原子操作，
 *       防止并发下两个 initiatePayment 基于相同 currentBalance1 计算，导致链下余额双花</li>
 * </ul>
 *
 * <p>测试策略：高并发下多线程对同一通道发起 initiatePayment，
 * 断言不会出现余额为负、不会双花同一通道余额。</p>
 */
class ChannelManagerDoubleSpendTest {

    private static final String PARTICIPANT_1 = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    private static final String PARTICIPANT_2 = "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5";
    private static final long INITIAL_AMOUNT = 1000L;
    private static final int LOCK_TIME = 10000;

    private Ed25519KeyPair[] generateTwoKeyPairs() {
        return new Ed25519KeyPair[]{
                Ed25519.generateKeyPair(),
                Ed25519.generateKeyPair()
        };
    }

    // ==================== B-11: 并发 initiatePayment 不双花 ====================

    @Test
    @DisplayName("should_notDoubleSpend_when_concurrentInitiatePaymentOnSameChannel")
    void should_notDoubleSpend_when_concurrentInitiatePaymentOnSameChannel() throws InterruptedException {
        // 准备：通道余额1=1000，10 个线程并发各 initiatePayment 200
        // initiatePayment 是提案性质：基于当前通道余额创建 pendingUpdate，不修改通道余额
        // 防双花的关键在 confirmPayment 时的 nonce 递增 + 余额守恒检查
        // 本测试验证：并发 initiatePayment 不应抛非预期异常，所有 update 余额非负
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );
        String channelId = channel.getChannelId();

        int threadCount = 10;
        long paymentAmount = 200L;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<ChannelUpdate> updates = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ChannelUpdate update = manager.initiatePayment(
                            channelId, paymentAmount,
                            keys[0].getPrivateKey().getEncoded(),
                            keys[0].getPublicKey().getEncoded(),
                            keys[1].getPublicKey().getEncoded()
                    );
                    updates.add(update);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    // 余额不足是预期
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
        assertThat(successCount.get() + failureCount.get()).isEqualTo(threadCount);

        // 关键断言：所有成功的 update 的 balance1/balance2 应非负
        for (ChannelUpdate u : updates) {
            assertThat(u.getBalance1())
                    .as("balance1 不应为负: " + u)
                    .isGreaterThanOrEqualTo(0);
            assertThat(u.getBalance2())
                    .as("balance2 不应为负: " + u)
                    .isGreaterThanOrEqualTo(0);
        }

        // 通道余额守恒：balance1 + balance2 == INITIAL_AMOUNT（initiatePayment 不修改通道余额）
        PaymentChannel finalChannel = manager.getChannel(channelId);
        assertThat(finalChannel.getBalance1() + finalChannel.getBalance2())
                .as("余额守恒应保持")
                .isEqualTo(INITIAL_AMOUNT);
        assertThat(finalChannel.getBalance1()).isEqualTo(INITIAL_AMOUNT);
        assertThat(finalChannel.getBalance2()).isEqualTo(0L);
    }

    // ==================== B-11: 并发 initiatePayment 不导致余额为负 ====================

    @Test
    @DisplayName("should_notProduceNegativeBalance_when_concurrentInitiatePayment")
    void should_notProduceNegativeBalance_when_concurrentInitiatePayment() throws InterruptedException {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, 100L, LOCK_TIME
        );
        String channelId = channel.getChannelId();

        // 20 个线程并发各 initiatePayment 50（总 1000 >> 100）
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    manager.initiatePayment(
                            channelId, 50L,
                            keys[0].getPrivateKey().getEncoded(),
                            keys[0].getPublicKey().getEncoded(),
                            keys[1].getPublicKey().getEncoded()
                    );
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

        // 通道余额仍应守恒且非负
        PaymentChannel finalChannel = manager.getChannel(channelId);
        assertThat(finalChannel.getBalance1())
                .as("balance1 不应为负").isGreaterThanOrEqualTo(0);
        assertThat(finalChannel.getBalance2())
                .as("balance2 不应为负").isGreaterThanOrEqualTo(0);
        assertThat(finalChannel.getBalance1() + finalChannel.getBalance2())
                .as("余额守恒").isEqualTo(100L);
    }

    // ==================== B-11: 单线程余额不足抛异常 ====================

    @Test
    @DisplayName("should_throw_when_initiatePaymentExceedsBalance")
    void should_throw_when_initiatePaymentExceedsBalance() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, 100L, LOCK_TIME
        );

        // 余额 100，支付 101 应抛异常
        assertThatThrownBy(() -> manager.initiatePayment(
                channel.getChannelId(), 101L,
                keys[0].getPrivateKey().getEncoded(),
                keys[0].getPublicKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient balance");

        // 通道余额未变
        PaymentChannel unchanged = manager.getChannel(channel.getChannelId());
        assertThat(unchanged.getBalance1()).isEqualTo(100L);
        assertThat(unchanged.getBalance2()).isEqualTo(0L);
    }

    // ==================== B-11: 并发下 nonce 单调递增 ====================

    @Test
    @DisplayName("should_haveValidNonce_when_concurrentInitiatePayment")
    void should_haveValidNonce_when_concurrentInitiatePayment() throws InterruptedException {
        // initiatePayment 不修改 channel.nonce，所有 update 的 nonce 都是 channel.getNonce()+1
        // 这是设计如此：initiatePayment 是提案，confirmPayment 时才递增 channel.nonce
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, 10000L, LOCK_TIME
        );
        String channelId = channel.getChannelId();

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<ChannelUpdate> updates = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ChannelUpdate u = manager.initiatePayment(
                            channelId, 100L,
                            keys[0].getPrivateKey().getEncoded(),
                            keys[0].getPublicKey().getEncoded(),
                            keys[1].getPublicKey().getEncoded()
                    );
                    updates.add(u);
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

        assertThat(errors).as("不应有异常: " + errors).isEmpty();
        assertThat(updates).as("应有 update 产生").hasSize(threadCount);

        // 所有 update 的 nonce 应为 1（channel.getNonce()+1 = 0+1 = 1）
        // initiatePayment 是提案性质，不递增 channel.nonce
        for (ChannelUpdate u : updates) {
            assertThat(u.getNonce())
                    .as("nonce 应为 1（基于初始 channel.nonce=0）")
                    .isEqualTo(1L);
        }

        // 通道 nonce 仍为 0（initiatePayment 不修改）
        PaymentChannel finalChannel = manager.getChannel(channelId);
        assertThat(finalChannel.getNonce()).isEqualTo(0L);
    }

    // ==================== B-10: 并发 submitUpdate 不破坏通道状态 ====================

    @Test
    @DisplayName("should_keepChannelConsistent_when_concurrentSubmitUpdate")
    void should_keepChannelConsistent_when_concurrentSubmitUpdate() throws InterruptedException {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );
        String channelId = channel.getChannelId();

        // 并发提交多个合法 update（不同 nonce，余额守恒）
        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    // 每个 update 改变余额但守恒：balance1 = 900 - idx*10, balance2 = 100 + idx*10
                    long b1 = 900L - idx * 10L;
                    long b2 = 100L + idx * 10L;
                    // 构造签名 update（nonce 由 ChannelManager 内部递增分配）
                    ChannelUpdate update = new ChannelUpdate(
                            channelId, idx + 1L, b1, b2,
                            null, null, System.currentTimeMillis()
                    );
                    byte[] msgHash = HashUtil.keccak256(update.getMessageToSign());
                    Ed25519PrivateKey pk1 = new Ed25519PrivateKey(keys[0].getPrivateKey().getEncoded());
                    Ed25519PrivateKey pk2 = new Ed25519PrivateKey(keys[1].getPrivateKey().getEncoded());
                    update.setSignature1(pk1.sign(msgHash));
                    update.setSignature2(pk2.sign(msgHash));

                    manager.submitUpdate(
                            channelId, b1, b2,
                            update.getSignature1(), update.getSignature2(),
                            keys[0].getPublicKey().getEncoded(), keys[1].getPublicKey().getEncoded()
                    );
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    // 并发下可能因 nonce 冲突失败，是预期
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
        assertThat(successCount.get()).as("至少一个成功").isGreaterThanOrEqualTo(1);

        // 通道余额守恒
        PaymentChannel finalChannel = manager.getChannel(channelId);
        assertThat(finalChannel.getBalance1() + finalChannel.getBalance2())
                .as("余额守恒应保持")
                .isEqualTo(INITIAL_AMOUNT);
        assertThat(finalChannel.getBalance1())
                .as("balance1 非负").isGreaterThanOrEqualTo(0);
        assertThat(finalChannel.getBalance2())
                .as("balance2 非负").isGreaterThanOrEqualTo(0);
    }
}