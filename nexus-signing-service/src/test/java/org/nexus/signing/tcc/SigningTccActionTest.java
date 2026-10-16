package org.nexus.signing.tcc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.signing.pool.NoncePool;
import org.nexus.signing.pool.NonceTestDbs;

import java.math.BigDecimal;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SigningTccActionImpl} 单元测试。
 *
 * <p>Phase 3 任务 #62：覆盖 TCC 三阶段核心路径与失败路径（设计文档 §4.2.4）。
 * <ul>
 *   <li>Try 成功：nonce 预锁定 + context 写入</li>
 *   <li>Try 失败：平台 keystore 未配置 / fromPubkey 不匹配 / RPC getNonce 失败 / 锁定冲突 → 抛 {@link TccPrepareException}</li>
 *   <li>Confirm 成功：签名 + 广播 + nonce 释放</li>
 *   <li>Cancel 成功：nonce 释放回 AVAILABLE</li>
 *   <li>Cancel 幂等：context 缺失时返回 true（Try 失败前未写入）</li>
 * </ul></p>
 *
 * <p>签名使用真实 {@code WalletUtils.fromPassword} 生成 keystore（与 {@code TxControllerTest} 一致），
 * 不 mock {@link TxUtils} 静态方法。</p>
 */
@ExtendWith(MockitoExtension.class)
public class SigningTccActionTest {

    @Mock
    private NoncePool noncePool;

    @Mock
    private NodeController nodeController;

    @Mock
    private PlatformKeystore platformKeystore;

    private SigningTccActionImpl tccAction;

    /** 真实 keystore 生成的签名材料（供 Confirm 测试用）。 */
    private String prikey;
    private String pubkey;
    private String toPubkeyHash;
    private String address;

    @BeforeEach
    public void setUp() {
        tccAction = new SigningTccActionImpl();
        tccAction.noncePool = noncePool;
        // #33：nonce 发放改为共享原子认领。注入**真**分配器（每用例一份独立 H2 库），
        // 于是"该地址首次认领 → 用 nodeController.getNonce 播种"成为被测路径的一部分，
        // 而不是被打桩成常数（那样 Try 里的取值语义就没人验了）。
        tccAction.nonceAllocator = NonceTestDbs.freshH2Allocator("tcc");
        tccAction.nodeController = nodeController;
        tccAction.platformKeystore = platformKeystore;

        // 生成真实签名材料（与 TxControllerTest 同款）
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        prikey = WalletUtils.obtainPrikey(keystoreJson, password);
        pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);
        assertNotNull(prikey, "prikey should not be empty");
        assertFalse(prikey.isEmpty(), "prikey should not be empty");
        String frompubhash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        toPubkeyHash = frompubhash;
        address = WalletUtils.pubkeyHashToAddress(frompubhash);
        assertEquals(0, WalletUtils.verifyAddress(address), "address should be valid");
    }

    // ==================== Try 阶段 ====================

    @Test
    public void testPrepareSignTransfer_success_nonceLocked() {
        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        long chainLastApplied = 42L;
        long allocated = 43L; // 节点规则：account.nonce 是「上一笔已应用」⇒ 下一个可用 = chain+1
        when(nodeController.getNonce(anyString())).thenReturn(NonceTestDbs.chainNonce(chainLastApplied));
        when(noncePool.lockNonce(eq(address), eq(allocated))).thenReturn(allocated);

        BusinessActionContext ctx = newCtx();
        BigDecimal amount = new BigDecimal("100");

        boolean result = tccAction.prepareSignTransfer(ctx, pubkey, toPubkeyHash, amount);

        assertTrue(result, "Try should succeed");
        verify(noncePool).lockNonce(eq(address), eq(allocated));
        // 验证 context 写入
        assertEquals(pubkey, ctx.getActionContext("fromPubkey", String.class));
        assertEquals(toPubkeyHash, ctx.getActionContext("toPubkeyHash", String.class));
        assertEquals(ctx.getActionContext("amount", String.class), "100");
        assertEquals(allocated, ctx.getActionContext("nonce", Long.class).longValue());
        assertEquals(address, ctx.getActionContext("address", String.class));
    }

    @Test
    public void testPrepareSignTransfer_success_rpcGetNonce_whenPoolEmpty() {
        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        // #33：该地址在本用例的库里是首次认领（哨兵态）⇒ 发放值 = 链上「上一笔已应用」+1，
        // 这正是"池为空时打 RPC"这条老语义的新落点（且只播一次种、并修了 off-by-one）。
        long rpcLastApplied = 7L;
        long allocated = 8L;
        JsonObject getNonceResp = new JsonObject();
        getNonceResp.addProperty("code", 2000);
        getNonceResp.addProperty("data", rpcLastApplied);
        when(nodeController.getNonce(anyString())).thenReturn(getNonceResp);
        when(noncePool.lockNonce(eq(address), eq(allocated))).thenReturn(allocated);

        BusinessActionContext ctx = newCtx();
        boolean result = tccAction.prepareSignTransfer(ctx, pubkey, toPubkeyHash, new BigDecimal("50"));

        assertTrue(result, "Try should succeed via RPC getNonce");
        verify(nodeController).getNonce(anyString());
        verify(noncePool).lockNonce(eq(address), eq(allocated));
        assertEquals(allocated, ctx.getActionContext("nonce", Long.class).longValue());
    }

    @Test
    public void testPrepareSignTransfer_noPlatformKey_throws() { assertThrows(TccPrepareException.class, () -> {
        when(platformKeystore.getPrikey()).thenReturn(null);
        tccAction.prepareSignTransfer(newCtx(), pubkey, toPubkeyHash, new BigDecimal("100"));
        });
    }

    @Test
    public void testPrepareSignTransfer_fromPubkeyMismatch_throws() { assertThrows(TccPrepareException.class, () -> {
        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(repeat('z', 64)); // 不匹配
        tccAction.prepareSignTransfer(newCtx(), pubkey, toPubkeyHash, new BigDecimal("100"));
        });
    }

    @Test
    public void testPrepareSignTransfer_rpcGetNonceFailed_throws() { assertThrows(TccPrepareException.class, () -> {
        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        JsonObject errResp = new JsonObject();
        errResp.addProperty("code", 5000); // RPC 失败 → 播种取不到值 → 分配器拒绝发放 → Try 失败
        when(nodeController.getNonce(anyString())).thenReturn(errResp);

        tccAction.prepareSignTransfer(newCtx(), pubkey, toPubkeyHash, new BigDecimal("100"));
        });
    }

    @Test
    public void testPrepareSignTransfer_lockConflict_throws() { assertThrows(TccPrepareException.class, () -> {
        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        // #33：nonce 由共享分配器发放（本用例首次认领 ⇒ 发放值 = 链上 42 + 1 = 43），
        // 真正的冲突来自 TCC 预锁定：本副本已有同值被锁 → lockNonce 返回 -1 → Try 必须失败
        when(nodeController.getNonce(anyString())).thenReturn(NonceTestDbs.chainNonce(42L));
        when(noncePool.lockNonce(eq(address), eq(43L))).thenReturn(-1L); // 锁定冲突

        tccAction.prepareSignTransfer(newCtx(), pubkey, toPubkeyHash, new BigDecimal("100"));
        });
    }

    // ==================== Confirm 阶段 ====================

    @Test
    public void testConfirmSignTransfer_success_signBroadcastRelease() {
        long nonce = 42L;
        // 准备 context（模拟 Try 阶段已写入）
        BusinessActionContext ctx = newCtx();
        ctx.addActionContext("fromPubkey", pubkey);
        ctx.addActionContext("toPubkeyHash", toPubkeyHash);
        ctx.addActionContext("amount", "100");
        ctx.addActionContext("nonce", nonce);
        ctx.addActionContext("address", address);

        when(platformKeystore.getPrikey()).thenReturn(prikey);
        // 广播返回 2000
        JsonObject broadcastResp = new JsonObject();
        broadcastResp.addProperty("code", 2000);
        when(nodeController.sendTransaction(anyString())).thenReturn(broadcastResp);
        when(noncePool.confirmNonce(eq(address), eq(nonce), anyString())).thenReturn(true);

        boolean result = tccAction.confirmSignTransfer(ctx);

        assertTrue(result, "Confirm should succeed");
        verify(noncePool).confirmNonce(eq(address), eq(nonce), anyString());
        verify(nodeController).sendTransaction(anyString());
        // 验证 txHash 写入 context
        String txHash = ctx.getActionContext("txHash", String.class);
        assertNotNull(txHash, "txHash should be written to context");
        assertFalse(txHash.isEmpty(), "txHash should not be empty");
    }

    @Test
    public void testConfirmSignTransfer_broadcastFailureStillConfirms() {
        long nonce = 10L;
        BusinessActionContext ctx = newCtx();
        ctx.addActionContext("fromPubkey", pubkey);
        ctx.addActionContext("toPubkeyHash", toPubkeyHash);
        ctx.addActionContext("amount", "100");
        ctx.addActionContext("nonce", nonce);
        ctx.addActionContext("address", address);

        when(platformKeystore.getPrikey()).thenReturn(prikey);
        // 广播抛异常（网络故障）
        when(nodeController.sendTransaction(anyString())).thenThrow(new RuntimeException("broadcast network error"));
        when(noncePool.confirmNonce(eq(address), eq(nonce), anyString())).thenReturn(true);

        boolean result = tccAction.confirmSignTransfer(ctx);

        // 广播失败不阻塞 Confirm（签名已完成，nonce 已使用；广播可由对账任务重试）
        assertTrue(result, "Confirm should still succeed even if broadcast fails");
        verify(noncePool).confirmNonce(eq(address), eq(nonce), anyString());
    }

    @Test
    public void testConfirmSignTransfer_missingContext_idempotentSuccess() {
        // context 为空（可能已 Confirm），应幂等返回 true
        BusinessActionContext ctx = newCtx();
        boolean result = tccAction.confirmSignTransfer(ctx);
        assertTrue(result, "Confirm with missing context should return true (idempotent)");
    }

    // ==================== Cancel 阶段 ====================

    @Test
    public void testCancelSignTransfer_success_nonceReleased() {
        long nonce = 42L;
        BusinessActionContext ctx = newCtx();
        ctx.addActionContext("nonce", nonce);
        ctx.addActionContext("address", address);

        when(noncePool.cancelNonce(eq(address), eq(nonce))).thenReturn(true);

        boolean result = tccAction.cancelSignTransfer(ctx);

        assertTrue(result, "Cancel should succeed");
        verify(noncePool).cancelNonce(eq(address), eq(nonce));
    }

    @Test
    public void testCancelSignTransfer_noLockRecord_idempotentSuccess() {
        long nonce = 42L;
        BusinessActionContext ctx = newCtx();
        ctx.addActionContext("nonce", nonce);
        ctx.addActionContext("address", address);

        // 无锁定记录（可能已 Cancel），cancelNonce 返回 false，但 Cancel 仍返回 true（幂等）
        when(noncePool.cancelNonce(eq(address), eq(nonce))).thenReturn(false);

        boolean result = tccAction.cancelSignTransfer(ctx);

        assertTrue(result, "Cancel should return true even if no lock record (idempotent)");
        verify(noncePool).cancelNonce(eq(address), eq(nonce));
    }

    @Test
    public void testCancelSignTransfer_missingContext_idempotentSuccess() {
        // context 为空（Try 阶段抛异常前未写入），应幂等返回 true
        BusinessActionContext ctx = newCtx();
        boolean result = tccAction.cancelSignTransfer(ctx);
        assertTrue(result, "Cancel with missing context should return true (Try failed before lock)");
    }

    // ==================== 辅助方法 ====================

    /** 创建一个带测试 xid 的 BusinessActionContext（seata 2.1.0 起 io.seata 子类仅无参构造，改用 setter）。 */
    private BusinessActionContext newCtx() {
        BusinessActionContext ctx = new BusinessActionContext();
        ctx.setXid("test-xid");
        ctx.setActionName("prepareSignTransfer");
        ctx.setActionContext(new HashMap<>());
        return ctx;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}