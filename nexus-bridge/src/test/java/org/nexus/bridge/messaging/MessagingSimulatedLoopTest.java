package org.nexus.bridge.messaging;

import org.junit.jupiter.api.Test;
import org.nexus.bridge.adapter.ChainAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-037 切片 3：模拟链路端到端测试。
 *
 * <p>覆盖"装配开关 + 模拟适配器 + 中继循环 + execute-enabled=true"全链路——
 * 消息入库（真实签名）→ findPending → 阈值验签 → 执行到<b>模拟链</b> → 记账
 * （EXECUTED + txHash）。不含任何外部网络/真实链凭证。</p>
 */
class MessagingSimulatedLoopTest {

    /**
     * 绑定注册器：plain context 不含 Boot 的 ConfigurationProperties 后处理，
     * 用 {@code @EnableConfigurationProperties} 启用既有 MessageConfig 绑定
     * （生产环境由 Boot 组件扫描直接绑定 {@code messageConfig}）。
     */
    @Configuration
    @EnableConfigurationProperties(MessageConfig.class)
    static class BoundConfigRegistrar {
    }

    @Test
    void simulatedClosedLoopExecutesAndRecordsTxHash() throws Exception {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(MessageStoreConfig.class, MessagingRelayConfig.class, BoundConfigRegistrar.class);
        Map<String, Object> props = new HashMap<>();
        props.put("nexus.bridge.messaging.relay-enabled", "true");
        props.put("nexus.bridge.messaging.required-signatures", "1");
        props.put("nexus.bridge.messaging.simulated-target-chains", "L2-sim");
        ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test-props", props));
        ctx.refresh();
        try {
            MessageFormatter formatter = ctx.getBean(MessageFormatter.class);
            MessageRelayer relayer = ctx.getBean(MessageRelayer.class);
            MessageExecutor executor = ctx.getBean(MessageExecutor.class);
            MessageConfig config = ctx.getBean(MessageConfig.class);
            MessageStore store = ctx.getBean(MessageStore.class);

            // 1) 模拟"入站"：真实签名后入库（relayMessage = 签名 + 入库一体）
            java.security.KeyPair kp = MessageRelayer.generateKeyPair();
            CrossChainMessage msg = formatter.formatMessage(
                    "L1-test", "L2-sim", "0xsource", "0xtarget",
                    new MessagePayload(MessagePayload.Type.ARBITRARY, "sim-payload").encode(),
                    1L, System.currentTimeMillis() / 1000L);
            relayer.relayMessage(msg, MessageRelayer.privateKeyToHex(kp));
            assertEquals(MessageStatus.RELAYED, msg.getStatus(),
                    "relayMessage 入库应为 RELAYED（已中继、待执行）");

            // 2) 中继循环消费 + 执行到模拟链 + 记账
            MessageRelayWorker worker =
                    new MessageRelayWorker(store, relayer, executor, config, 50, true);
            assertEquals(1, worker.runOnce());

            String txHash = store.getExecutionTxHash(msg.getMessageId()).orElse(null);
            assertNotNull(txHash, "模拟链路必须产出 txHash 并记账");
            assertTrue(txHash.startsWith("0x"), "txHash 应为 hex 风格：" + txHash);
            assertEquals(MessageStatus.EXECUTED,
                    store.findById(msg.getMessageId()).get().getStatus());

            // 3) 模拟链回执可查（内存态），未知哈希为 null
            ChainAdapter sim = executor.getTargetAdapters().get("L2-sim");
            assertNotNull(sim, "模拟链应已装配进注册表");
            assertNotNull(sim.getTransactionReceipt(txHash), "已提交交易应可查回执");
            assertNull(sim.getTransactionReceipt("0xdeadbeef"));

            // 4) 幂等：执行完成的下一轮无待处理
            assertEquals(0, worker.runOnce(), "执行完成后不应再消费");
        } finally {
            ctx.close();
        }
    }
}
