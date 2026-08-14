package org.nexus.consensus.pos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.nexus.keystore.crypto.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #20/PLAN-008 专项：验证人固定密钥（nexus.consensus.validator-private-key）。
 *
 * <p>验证：Spring 注入配置私钥后引擎地址稳定（重启不变），可预生成验证人加入多节点拓扑；
 * 未配置时回退随机生成（默认行为）。</p>
 */
class PosConsensusEngineKeyTest {

    private static final String FIXED_PRIV_HEX =
            "1122334455667788990011223344556677889900112233445566778899001122";

    @AfterEach
    void tearDown() {
        System.clearProperty("nexus.consensus.validator-private-key");
    }

    /** 通过反射注入配置密钥字段（模拟 Spring @Value 注入）。 */
    private void injectConfiguredKey(PosConsensusEngine engine, String hex) throws Exception {
        java.lang.reflect.Field f = PosConsensusEngine.class.getDeclaredField("configuredValidatorPrivateKeyHex");
        f.setAccessible(true);
        f.set(engine, hex);
        engine.applyConfiguredKey();  // 触发 @PostConstruct 等价逻辑
    }

    @Test
    void configuredPrivateKeyYieldsStablePublicKey() throws Exception {
        PosConsensusEngine e1 = new PosConsensusEngine();
        PosConsensusEngine e2 = new PosConsensusEngine();  // 模拟重启
        injectConfiguredKey(e1, FIXED_PRIV_HEX);
        injectConfiguredKey(e2, FIXED_PRIV_HEX);

        KeyPair k1 = e1.getSigningKeyPair();
        KeyPair k2 = e2.getSigningKeyPair();
        assertNotNull(k1);
        assertArrayEquals(k1.getPublicKey().getBytes(), k2.getPublicKey().getBytes(),
                "固定私钥 → 重启后公钥应稳定（验证人地址不变）");
        assertArrayEquals(k1.getPrivateKey().getBytes(), k2.getPrivateKey().getBytes());
    }

    @Test
    void noConfiguredKeyFallsBackToRandom() throws Exception {
        PosConsensusEngine e1 = new PosConsensusEngine();
        PosConsensusEngine e2 = new PosConsensusEngine();

        KeyPair k1 = e1.getSigningKeyPair();
        KeyPair k2 = e2.getSigningKeyPair();
        assertNotNull(k1);
        assertFalse(java.util.Arrays.equals(k1.getPrivateKey().getBytes(), k2.getPrivateKey().getBytes()),
                "未配置私钥 → 每次随机生成（默认行为）");
    }

    @Test
    void invalidConfiguredKeyFallsBackToRandom() throws Exception {
        PosConsensusEngine e = new PosConsensusEngine();
        injectConfiguredKey(e, "zz-not-hex");  // 非法 hex → 保持随机，不崩溃
        assertNotNull(e.getSigningKeyPair());
    }
}