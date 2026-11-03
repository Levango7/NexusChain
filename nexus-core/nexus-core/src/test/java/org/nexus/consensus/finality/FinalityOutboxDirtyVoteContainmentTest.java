package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.JdbcFinalityStateStore;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-071：{@code finality_vote_outbox} <b>不会</b>把脏票传播到最终性视图的门禁。
 *
 * <h2>为什么需要它</h2>
 * <p>PLAN-071 收尾时登记了一条遗留项：「{@code finality_vote_outbox} 的脏行传播性
 * 需先确认」。本用例把确认结果固化成回归断言，防止将来有人改动 outbox 重放路径时
 * 无意中破坏它。</p>
 *
 * <h2>结论（实测，2026-11-03）</h2>
 * <ol>
 *   <li><b>脏票能进 outbox</b>：{@code enqueue()} 只挡 null 票，<b>不校验</b>
 *       checkpoint 哈希长度 ⇒ 一条 3 字节哈希的票可以入队、也会落进
 *       {@code finality_vote_outbox} 表；</li>
 *   <li><b>重放时被正常拒绝</b>：{@code flush()} → {@code gadget.submitVote(vote)}，
 *       而 {@code submitVote} 内部先走 {@code validateVoteBinding}（含 32 字节校验），
 *       脏票返回 <b>正常结果</b>（不抛异常）；</li>
 *   <li><b>因此不是毒丸</b>：{@code tryFlush} 只在 <b>抛异常</b>时才把票留在队列里重试；
 *       正常返回 ⇒ 票被移除。实测连续 3 轮 flush 后内存队列与 outbox 表都归零；</li>
 *   <li><b>不污染任何表</b>：{@code finality_votes} / {@code finality_checkpoints}
 *       行数均为 0，{@code isFinalized()} 为 false，内存 voter 集合为空。</li>
 * </ol>
 *
 * <p>⇒ 与 {@code finality_votes} / {@code finality_checkpoints} 不同，
 * {@code finality_vote_outbox} <b>无需清理</b>：脏票会被自然拒绝并移除。
 * 这也解释了为什么 PLAN-069/071 只处理那两张表。</p>
 *
 * @since 2.82.0
 */
class FinalityOutboxDirtyVoteContainmentTest {

    private static final byte[] DIRTY_CHECKPOINT = new byte[]{1, 2, 3};
    private static final long EPOCH = 5L;
    private static final String VALIDATOR = "v-outbox-containment";

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private JdbcTemplate jdbc;
    private JdbcFinalityStateStore store;
    private FinalityGadget gadget;
    private Ed25519KeyPair keyPair;

    private void setUp() throws Exception {
        // 每个用例一个独立内存库：避免两个用例共享同一库、前一个用例的残留行
        // 让后一个用例以奇怪的方式失败。
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:outbox_containment_" + DB_SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        store = new JdbcFinalityStateStore(jdbc);
        store.init();

        ValidatorRegistry registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        StakingServiceImpl staking = new StakingServiceImpl();
        Field f = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
        f.setAccessible(true);
        f.set(staking, registry);

        keyPair = Ed25519.generateKeyPair();
        registry.register(VALIDATOR,
                Hex.encodeHexString(keyPair.getPublicKey().getEncoded()),
                new BigDecimal("300"), 0.1);
        Validator v = registry.getValidator(VALIDATOR);
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(VALIDATOR, new BigDecimal("300"));

        gadget = new FinalityGadget(registry, staking, store);
    }

    @Test
    void dirtyVoteIsRejectedOnReplayAndLeavesNoTrace() throws Exception {
        setUp();

        // 签名完全有效——只有检查点哈希长度非法（3 字节）。这样才能证明是
        // **长度闸**在起作用，而不是"签名坏了"这种无关原因。
        Vote dirty = signedVote(DIRTY_CHECKPOINT);

        FinalityOutboxManager outbox = new FinalityOutboxManager(gadget);
        outbox.enqueueReceived(dirty, new RuntimeException("simulated write failure"));

        // ① 脏票确实能入队（写入侧不校验）——这是本用例的前提，必须断言，
        //    否则将来有人给 enqueue 加了校验会让本用例"假绿"。
        assertEquals(1, outbox.pendingCount(), "前提：outbox 写入侧不校验哈希长度，脏票可入队");
        assertEquals(1, count("finality_vote_outbox"), "前提：脏票会落进持久化 outbox 表");

        // ② 真实 flush（生产路径），不直接调 submitVote
        outbox.flush();

        // ③ 不是毒丸：正常拒绝 ⇒ 票被移除（不是"每 5 秒重试一条永远失败的票"）
        assertEquals(0, outbox.pendingCount(), "脏票被正常拒绝后必须从内存队列移除");
        assertEquals(0, count("finality_vote_outbox"),
                "脏票被正常拒绝后必须从 outbox 表移除——否则会成为每 5s 重试的毒丸");

        // ④ 不污染任何最终性表
        assertEquals(0, count("finality_votes"), "脏票不得落进 finality_votes");
        assertEquals(0, count("finality_checkpoints"), "脏票不得落进 finality_checkpoints");
        assertFalse(gadget.isFinalized(EPOCH, DIRTY_CHECKPOINT),
                "脏检查点不得被报告为已最终化");
        assertTrue(votersEmpty(), "脏票不得在内存里留下 voter 集合");
    }

    @Test
    void repeatedFlushStaysClean() throws Exception {
        setUp();

        FinalityOutboxManager outbox = new FinalityOutboxManager(gadget);
        outbox.enqueueReceived(signedVote(DIRTY_CHECKPOINT), new RuntimeException("simulated"));

        // 模拟多轮定时任务：不能出现"越跑越多"或"残留"
        for (int i = 0; i < 3; i++) {
            outbox.flush();
            assertEquals(0, outbox.pendingCount(), "第 " + (i + 1) + " 轮后内存队列必须为空");
            assertEquals(0, count("finality_vote_outbox"),
                    "第 " + (i + 1) + " 轮后 outbox 表必须为空（不留毒丸）");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Vote signedVote(byte[] checkpoint) {
        byte[] publicKey = keyPair.getPublicKey().getEncoded();
        Vote unsigned = new Vote(EPOCH, checkpoint, VALIDATOR, new byte[0], publicKey);
        try {
            byte[] signature = keyPair.getPrivateKey().sign(unsigned.signingPayload());
            return new Vote(EPOCH, checkpoint, VALIDATOR, signature, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed for test vote", e);
        }
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? -1 : n;
    }

    @SuppressWarnings("unchecked")
    private boolean votersEmpty() throws Exception {
        Field f = FinalityGadget.class.getDeclaredField("epochCheckpointVoters");
        f.setAccessible(true);
        return ((java.util.Map<String, ?>) f.get(gadget)).isEmpty();
    }
}
