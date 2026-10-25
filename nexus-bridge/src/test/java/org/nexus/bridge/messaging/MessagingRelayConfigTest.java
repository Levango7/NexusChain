package org.nexus.bridge.messaging;

import org.junit.jupiter.api.Test;
import org.nexus.bridge.adapter.ChainAdapter;
import org.nexus.bridge.adapter.SimulatedChainAdapter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥 messaging 装配开关测试（PLAN-037 切片 1）。
 *
 * <p>锁定三件事：</p>
 * <ol>
 *   <li><b>默认关</b>：不建任何 messaging Bean（既有部署行为零变化）；</li>
 *   <li><b>开启</b>：relayer/executor/status 就位，且 relayer 能真实完成
 *       "签名 → 多签验签" 闭环（不是空壳 Bean）；</li>
 *   <li><b>状态可观测</b>：status 快照反映 store 实现与适配器数量。</li>
 * </ol>
 */
class MessagingRelayConfigTest {

    @Configuration
    static class FakeAdapterConfig {
        @Bean
        ChainAdapter fakeChainAdapter() {
            return new ChainAdapter() {
                @Override public String getChainId() { return "L2-fake"; }
                @Override public long getBlockHeight() { return 42L; }
                @Override public String sendTransaction(byte[] tx) { return "0xfake"; }
                @Override public Object getTransactionReceipt(String hash) { return "0x01"; }
                @Override public String callContract(String address, String data) { return "0x"; }
            };
        }
    }

    private AnnotationConfigApplicationContext newContext(Map<String, Object> props, Class<?>... extra) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(MessageStoreConfig.class, MessagingRelayConfig.class);
        if (extra.length > 0) {
            ctx.register(extra);
        }
        if (!props.isEmpty()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test-props", new HashMap<>(props)));
        }
        ctx.refresh();
        return ctx;
    }

    @Test
    void disabledByDefaultCreatesNoMessagingBeans() {
        try (AnnotationConfigApplicationContext ctx = newContext(Map.of())) {
            assertNotNull(ctx.getBean(MessageStore.class), "store 开关独立存在（默认 memory）");
            assertNull(ctx.getBeanProvider(MessageRelayer.class).getIfAvailable(),
                    "默认不装配 relayer —— 既有行为零变化");
            assertNull(ctx.getBeanProvider(MessageExecutor.class).getIfAvailable());
            assertNull(ctx.getBeanProvider(BridgeMessagingStatus.class).getIfAvailable());
        }
    }

    @Test
    void enabledProvidesRelayerThatCanSignAndVerify() {
        try (AnnotationConfigApplicationContext ctx = newContext(
                Map.of("nexus.bridge.messaging.relay-enabled", "true",
                       "nexus.bridge.messaging.required-signatures", "1"),
                FakeAdapterConfig.class)) {
            MessageFormatter formatter = ctx.getBean(MessageFormatter.class);
            MessageRelayer relayer = ctx.getBean(MessageRelayer.class);
            MessageExecutor executor = ctx.getBean(MessageExecutor.class);
            BridgeMessagingStatus status = ctx.getBean(BridgeMessagingStatus.class);
            assertNotNull(formatter);
            assertNotNull(relayer);
            assertNotNull(executor);

            // relayer 真实签名闭环（不是空壳 Bean）
            java.security.KeyPair kp = MessageRelayer.generateKeyPair();
            CrossChainMessage msg = formatter.formatMessage(
                    "L1-src", "L2-fake", "0xsrc", "0xdst",
                    new MessagePayload(MessagePayload.Type.ARBITRARY, "amount=7").encode(),
                    1L, 0L);
            MessageRelayRecord rec = relayer.relayMessage(msg, MessageRelayer.privateKeyToHex(kp));
            List<String> validatorKeys = List.of(MessageRelayer.publicKeyToHex(kp));
            // relayMessage 会向 msg 追加签名；验签直接用 msg（record 只留存 messageId 审计字段）
            assertTrue(relayer.verifySignatures(msg, 1, validatorKeys),
                    "装配出的 relayer 必须能签名并过多签验签");
            assertTrue(relayer.checkDuplicate(msg.getMessageId()),
                    "中继后消息应已在 store 中去重");
            assertNotNull(rec.getSignature());

            // 状态快照：注入的是 memory store、fake adapter 已登记
            assertEquals("InMemoryMessageStore", status.storeType());
            assertEquals(1, status.targetAdapters());
            assertEquals(1, status.requiredSignatures());
            assertTrue(status.relayEnabled());
            // 切片 3 键语义修正：注册表键 = chainId（此前是 Spring 收集的 bean 名，
            // 与 MessageExecutor 按 targetChain 查找的语义不一致）
            assertEquals("L2-fake", executor.getTargetAdapters().get("L2-fake").getChainId(),
                    "注册表键应为 chainId，而非 bean 名");
        }
    }

    @Test
    void simulatedTargetChainsAreAssembledIntoExecutorRegistry() {
        try (AnnotationConfigApplicationContext ctx = newContext(
                Map.of("nexus.bridge.messaging.relay-enabled", "true",
                       "nexus.bridge.messaging.required-signatures", "1",
                       "nexus.bridge.messaging.simulated-target-chains", "L2-sim, L3-sim"))) {
            MessageExecutor executor = ctx.getBean(MessageExecutor.class);
            BridgeMessagingStatus status = ctx.getBean(BridgeMessagingStatus.class);
            assertEquals(2, status.targetAdapters());
            assertTrue(executor.getTargetAdapters().get("L2-sim") instanceof SimulatedChainAdapter);
            assertTrue(executor.getTargetAdapters().get("L3-sim") instanceof SimulatedChainAdapter);
        }
    }

    @Test
    void containerAdapterWinsOverSimulatedForSameChainId() {
        // 合并规则：真实（容器收集）优先、模拟不覆盖同名链
        try (AnnotationConfigApplicationContext ctx = newContext(
                Map.of("nexus.bridge.messaging.relay-enabled", "true",
                       "nexus.bridge.messaging.required-signatures", "1",
                       "nexus.bridge.messaging.simulated-target-chains", "L2-fake, L3-sim"),
                FakeAdapterConfig.class)) {
            MessageExecutor executor = ctx.getBean(MessageExecutor.class);
            assertEquals(2, executor.getTargetAdapters().size());
            assertFalse(executor.getTargetAdapters().get("L2-fake") instanceof SimulatedChainAdapter,
                    "同名链必须保留容器适配器，模拟链不得覆盖");
            assertTrue(executor.getTargetAdapters().get("L3-sim") instanceof SimulatedChainAdapter);
        }
    }

    @Test
    void zeroSignaturesFallsBackToDefaultNotCrash() {
        // 显式配 0/负值：不得让 MessageConfig 进入非法态（verifySignatures 会拒绝 <=0）
        try (AnnotationConfigApplicationContext ctx = newContext(
                Map.of("nexus.bridge.messaging.relay-enabled", "true",
                       "nexus.bridge.messaging.required-signatures", "0"))) {
            MessageConfig config = ctx.getBean(MessageConfig.class);
            assertEquals(2, config.getRequiredSignatures(),
                    "0 视为未配置：保持 MessageConfig 默认 2 签");
        }
    }
}
