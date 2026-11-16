package org.nexus.consensus.finality.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A-7（roadmap #27「epoch 窗口 / 规范检查点语义校验」）的回归。
 *
 * <p>审计器的价值在于<b>能发现错</b>，因此每个用例都构造一种"库与链不一致"的形态，
 * 并断言它被点名（而不是静默 OK）：</p>
 * <ol>
 *   <li>一致 ⇒ OK；</li>
 *   <li>库内哈希与规范块不同 ⇒ HASH_MISMATCH（含 stored/canonical 双哈希证据）；</li>
 *   <li>epoch 反算高度超出链头 ⇒ HEIGHT_OUT_OF_RANGE（非 32 倍数配置的典型形态）；</li>
 *   <li>中间 epoch 缺记录 ⇒ EPOCH_GAP；</li>
 *   <li>未装配 FinalityStateStore ⇒ UNKNOWN（fail-visible，不是"没数据所以我绿"）。</li>
 * </ol>
 */
class FinalityCheckpointAuditorTest {

    private static final long EPOCH_LEN = 4L;

    private ValidatorRegistry registry;
    private StakingService staking;
    private InMemoryFinalityStateStore store;
    private FinalityGadget gadget;
    private NexusChainBlockChain chain;
    private final Map<Long, Block> blocksByHeight = new HashMap<>();

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking(staking, registry);
        Ed25519KeyPair kp = Ed25519.generateKeyPair();
        registry.register("v1", org.apache.commons.codec.binary.Hex.encodeHexString(kp.getPublicKey().getEncoded()),
                new BigDecimal(1000), 0.1);
        Validator v = registry.getValidator("v1");
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake("v1", new BigDecimal(1000));

        store = new InMemoryFinalityStateStore();
        gadget = new FinalityGadget(registry, staking, store);

        chain = mock(NexusChainBlockChain.class);
        when(chain.getCanonicalBlock(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    long h = inv.getArgument(0);
                    return blocksByHeight.get(h);
                });
    }

    /** 造一个头部字段唯一、因而 keccak256 哈希唯一的块（{@code Block.getHash()} 无 setter）。 */
    private Block blockAt(long height, int seed) {
        Block b = new Block();
        b.nVersion = 1;
        b.hashPrevBlock = new byte[Block.HASH_SIZE];
        b.hashMerkleRoot = new byte[Block.HASH_SIZE];
        b.hashMerkleState = new byte[Block.HASH_SIZE];
        b.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        b.nBits = new byte[Block.HASH_SIZE];
        b.nNonce = fixedHash(seed);
        b.nHeight = height;
        b.nTime = 1_700_000_000L + seed;
        b.reHash();
        return b;
    }

    /** 登记某高度的规范块并返回其哈希。 */
    private byte[] canon(long height, int seed) {
        Block b = blockAt(height, seed);
        blocksByHeight.put(height, b);
        return b.getHash();
    }

    private byte[] fixedHash(int seed) {
        byte[] h = new byte[Block.HASH_SIZE];
        for (int i = 0; i < h.length; i++) {
            h[i] = (byte) (seed + i);
        }
        return h;
    }

    @Test
    void consistentStoreAndChainReportsOk() {
        for (long epoch = 1; epoch <= 3; epoch++) {
            long height = epoch * EPOCH_LEN;
            byte[] hash = canon(height, (int) epoch);
            store.markFinalized(epoch, hash);
        }
        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(gadget, chain, EPOCH_LEN);
        Map<String, Object> report = auditor.audit(12L);

        assertEquals("OK", report.get("verdict"), "一致时不许报红：" + report);
        @SuppressWarnings("unchecked")
        Map<FinalityCheckpointAuditor.Verdict, Integer> tally =
                (Map<FinalityCheckpointAuditor.Verdict, Integer>) report.get("tally");
        assertEquals(3, tally.get(FinalityCheckpointAuditor.Verdict.OK));
    }

    @Test
    void storedHashDifferentFromCanonicalIsReported() {
        // epoch 2：库内存 X，链上规范块是 Y ⇒ HASH_MISMATCH（这是本审计的核心收益）
        byte[] h4 = canon(4L, 1);
        byte[] h8 = canon(8L, 9);   // 规范块
        store.markFinalized(1, h4);
        store.markFinalized(2, fixedHash(77));   // 与规范块不同

        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(gadget, chain, EPOCH_LEN);
        Map<String, Object> report = auditor.audit(8L);

        assertEquals("FAIL", report.get("verdict"));
        @SuppressWarnings("unchecked")
        List<FinalityCheckpointAuditor.Check> checks =
                (List<FinalityCheckpointAuditor.Check>) report.get("checks");
        FinalityCheckpointAuditor.Check bad = checks.stream()
                .filter(c -> c.epoch() == 2L).findFirst().orElseThrow();
        assertEquals(FinalityCheckpointAuditor.Verdict.HASH_MISMATCH, bad.verdict());
        assertNotNull(bad.storedHash());
        assertNotNull(bad.canonicalHash());
        assertTrue(bad.detail().contains("stored=") && bad.detail().contains("canonical="),
                "必须给出双哈希证据：" + bad.detail());
    }

    @Test
    void epochBeyondHeadIsReportedAsOutOfRange() {
        // 库内存了 epoch 5（高度 20），但链头只有 12 ⇒ 配置错位/跨分支残留形态
        byte[] h4 = canon(4L, 1);
        store.markFinalized(1, h4);
        store.markFinalized(5, fixedHash(55));

        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(gadget, chain, EPOCH_LEN);
        Map<String, Object> report = auditor.audit(12L);

        assertEquals("FAIL", report.get("verdict"));
        @SuppressWarnings("unchecked")
        List<FinalityCheckpointAuditor.Check> checks =
                (List<FinalityCheckpointAuditor.Check>) report.get("checks");
        FinalityCheckpointAuditor.Check oob = checks.stream()
                .filter(c -> c.epoch() == 5L).findFirst().orElseThrow();
        assertEquals(FinalityCheckpointAuditor.Verdict.HEIGHT_OUT_OF_RANGE, oob.verdict());
        assertEquals(20L, oob.height());
    }

    @Test
    void missingEpochInSequenceIsReportedAsGap() {
        byte[] h4 = canon(4L, 1);
        byte[] h12 = canon(12L, 3);
        store.markFinalized(1, h4);
        store.markFinalized(3, h12);   // epoch 2 缺记录

        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(gadget, chain, EPOCH_LEN);
        Map<String, Object> report = auditor.audit(12L);

        assertEquals("FAIL", report.get("verdict"));
        assertEquals(1L, report.get("gapEpochs"));
        @SuppressWarnings("unchecked")
        List<FinalityCheckpointAuditor.Check> checks =
                (List<FinalityCheckpointAuditor.Check>) report.get("checks");
        FinalityCheckpointAuditor.Check gap = checks.stream()
                .filter(c -> c.verdict() == FinalityCheckpointAuditor.Verdict.EPOCH_GAP)
                .findFirst().orElseThrow();
        assertEquals(2L, gap.epoch());
    }

    @Test
    void noStateStoreIsUnknownNotOk() {
        // 真·无装配：gadget 为 null（非 pos 拓扑/单元场景）。
        // 注意不能靠 `new FinalityGadget(..., null)` 造"无库"——构造器内部会回退成
        // InMemoryFinalityStateStore（PLAN-015 的既定语义），那样拿到的是空库而非"无库"。
        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(null, chain, EPOCH_LEN);
        Map<String, Object> report = auditor.audit(12L);
        assertEquals("UNKNOWN", report.get("verdict"),
                "无库可核验时必须报 UNKNOWN（fail-visible），不得报 OK");
    }

    @Test
    void staleRowAlongsideCorrectRowIsReported() {
        // 2026-11-16 真 PG 演习实证：启动时的补最终化扫描会据票重新推导出"正确行"，
        // 而跨分支/篡改残留的"错行"仍留在库里 ⇒ 同一 epoch 同时有两行。
        // 逐行判定必须把错行报出来（按 epoch 合并"任一匹配即 OK"会放过垃圾行）。
        byte[] h4 = canon(4L, 1);
        byte[] h8 = canon(8L, 9);
        store.markFinalized(1, h4);
        store.markFinalized(2, h8);            // 正确行（自愈推导出来的那条）
        store.markFinalized(2, fixedHash(66)); // 残留错行

        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(gadget, chain, EPOCH_LEN);
        Map<String, Object> report = auditor.audit(8L);

        assertEquals("FAIL", report.get("verdict"), "错行必须被报出：" + report);
        assertEquals(2, ((Number) report.get("recordedEpochs")).intValue());
        assertEquals(3, ((Number) report.get("recordedRows")).intValue());
        @SuppressWarnings("unchecked")
        Map<FinalityCheckpointAuditor.Verdict, Integer> tally =
                (Map<FinalityCheckpointAuditor.Verdict, Integer>) report.get("tally");
        assertEquals(1, tally.get(FinalityCheckpointAuditor.Verdict.HASH_MISMATCH));
        assertEquals(2, tally.get(FinalityCheckpointAuditor.Verdict.OK));
    }

    @Test
    void trailingLagIsNotReportedAsGap() {
        // 最终性天然落后链头若干 epoch（轮转 + 视图切换；PLAN-047 基线 lag≈2）。
        // 尾随未最终化不是数据缺口，只能报成 finalityLagEpochs 信息项。
        byte[] h4 = canon(4L, 1);
        byte[] h8 = canon(8L, 2);
        store.markFinalized(1, h4);
        store.markFinalized(2, h8);

        FinalityCheckpointAuditor auditor = new FinalityCheckpointAuditor(gadget, chain, EPOCH_LEN);
        // head=30、epochLength=4 ⇒ headEpoch=(30-1)/4+1=8；最高已记录 epoch=2 ⇒ 尾随差 6
        Map<String, Object> report = auditor.audit(30L);

        assertEquals("OK", report.get("verdict"),
                "尾随滞后不是缺口，不得报红：" + report);
        assertEquals(0L, report.get("gapEpochs"));
        assertEquals(6L, report.get("finalityLagEpochs"));
    }

    private void injectRegistryIntoStaking(StakingService target, ValidatorRegistry targetRegistry) {
        try {
            var field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(target, targetRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
