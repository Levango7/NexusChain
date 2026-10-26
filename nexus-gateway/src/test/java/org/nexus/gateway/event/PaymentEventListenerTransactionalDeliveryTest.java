package org.nexus.gateway.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护测试（2026-10-27）：支付/退款事件必须在**事务提交后**异步投递。
 *
 * <p>背景：{@code PaymentServiceImpl} 在事务内发布事件。若监听器只用
 * {@code @EventListener}，异步线程可能在订单提交前就读取订单并发送 webhook
 * （回调地址取不到商户 notifyUrl、回滚的单据也会通知）。因此两个监听方法必须
 * 同时满足：{@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)}，
 * 且 {@code fallbackExecution = true} 以保持无事务场景（补偿任务/测试）可用。</p>
 *
 * <p>纯反射断言，不加载 Spring 上下文。</p>
 */
class PaymentEventListenerTransactionalDeliveryTest {

    private static void assertAfterCommitAsync(Class<?> eventType, String methodName, Class<?>... params)
            throws NoSuchMethodException {
        Method method = PaymentEventListener.class.getDeclaredMethod(methodName, params);

        assertThat(method.isAnnotationPresent(Async.class))
                .as("%s 必须 @Async（不得阻塞支付确认线程）", methodName)
                .isTrue();

        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);
        assertThat(listener)
                .as("%s 必须使用 @TransactionalEventListener（提交后投递）", methodName)
                .isNotNull();
        assertThat(listener.phase())
                .as("%s 必须在事务提交后投递", methodName)
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution())
                .as("%s 必须在无事务上下文时仍执行（补偿/测试路径）", methodName)
                .isTrue();
        assertThat(method.getParameterTypes())
                .as("%s 必须且只能监听 %s", methodName, eventType.getSimpleName())
                .containsExactly(eventType);
    }

    @Test
    @DisplayName("支付确认监听器：@Async + AFTER_COMMIT + fallbackExecution")
    void paymentConfirmedIsDeliveredAfterCommit() throws Exception {
        assertAfterCommitAsync(PaymentConfirmedEvent.class, "onPaymentConfirmed", PaymentConfirmedEvent.class);
    }

    @Test
    @DisplayName("退款完成监听器：@Async + AFTER_COMMIT + fallbackExecution")
    void refundCompletedIsDeliveredAfterCommit() throws Exception {
        assertAfterCommitAsync(RefundCompletedEvent.class, "onRefundCompleted", RefundCompletedEvent.class);
    }
}
