package org.nexus.gateway.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AsyncWebhookDispatcher} 单元测试。
 *
 * <p>dispatch 在线程池中异步执行，且当前实现 deliverWithRetry 的 try 块不会抛异常
 * （仅日志输出），因此不会进入重试/死信分支。这里仅验证 dispatch 不阻塞、
 * getDeadLetterCount 在正常路径下为 0。</p>
 */
class AsyncWebhookDispatcherTest {

    @Test
    @DisplayName("dispatch: 正常提交不产生死信")
    void dispatch_normal_noDeadLetter() throws Exception {
        AsyncWebhookDispatcher dispatcher = new AsyncWebhookDispatcher();
        dispatcher.dispatch("https://merchant.example/hook",
                Map.of("event", "payment.succeeded", "payment_id", "pay_1"),
                "sig");

        // 等待异步任务完成（线程池 4 线程，任务极快）
        Thread.sleep(100);
        assertEquals(0, dispatcher.getDeadLetterCount());
    }

    @Test
    @DisplayName("dispatch: 多次提交仍无死信（成功路径）")
    void dispatch_multiple_noDeadLetter() throws Exception {
        AsyncWebhookDispatcher dispatcher = new AsyncWebhookDispatcher();
        for (int i = 0; i < 5; i++) {
            dispatcher.dispatch("https://merchant.example/hook",
                    Map.of("i", i), "sig");
        }
        Thread.sleep(200);
        assertEquals(0, dispatcher.getDeadLetterCount());
    }
}