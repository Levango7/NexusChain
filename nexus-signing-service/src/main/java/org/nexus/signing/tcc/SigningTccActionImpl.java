package org.nexus.signing.tcc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.signing.pool.NoncePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link SigningTccAction} 实现：signing-service TCC 分支事务。
 *
 * <p>Phase 3 任务 #62：与 gateway 侧 AT 模式混合，实现签名+广播的分布式事务一致性
 * （设计文档 §4.2.4 / §3.1.2 决策理由 2）。</p>
 *
 * <p>三阶段实现：
 * <ul>
 *   <li><b>Try</b>：预锁定 nonce（{@code NoncePool.lockNonce}），不签名不广播。
 *       nonce 获取失败抛 {@link TccPrepareException} 触发全局回滚。</li>
 *   <li><b>Confirm</b>：签名（{@code TxUtils.ClientToTransferAccount}）+ 广播
 *       （{@code NodeController.sendTransaction}）+ 释放 nonce（{@code NoncePool.confirmNonce}）。
 *       返回交易哈希写入 context 供调用方查询。</li>
 *   <li><b>Cancel</b>：释放 nonce（{@code NoncePool.cancelNonce}），nonce 回到 AVAILABLE。</li>
 * </ul></p>
 *
 * <p>幂等性：Confirm/Cancel 可能被 Seata TM 重试，{@code NoncePool} 已处理无锁定记录场景
 * （返回 false 但不抛异常），本实现亦不抛异常，保证重试安全。</p>
 *
 * <p>安全：与 {@code TxController} 一致，签名仅使用服务端 {@link PlatformKeystore}，
 * {@code fromPubkey} 必须匹配平台公钥，不接受调用方私钥（P1 修复）。</p>
 */
@Component
public class SigningTccActionImpl implements SigningTccAction {

    private static final Logger log = LoggerFactory.getLogger(SigningTccActionImpl.class);

    /** BusinessActionContext 键名常量（Try 写入，Confirm/Cancel 读取）。 */
    private static final String CTX_FROM_PUBKEY = "fromPubkey";
    private static final String CTX_TO_PUBKEY_HASH = "toPubkeyHash";
    private static final String CTX_AMOUNT = "amount";
    private static final String CTX_NONCE = "nonce";
    private static final String CTX_ADDRESS = "address";
    private static final String CTX_TX_HASH = "txHash";

    @Autowired
    NoncePool noncePool;

    @Autowired
    NodeController nodeController;

    @Autowired
    PlatformKeystore platformKeystore;

    // ==================== Try：预锁定 nonce ====================

    @Override
    public boolean prepareSignTransfer(BusinessActionContext actionContext,
                                       String fromPubkey,
                                       String toPubkeyHash,
                                       BigDecimal amount) {
        String xid = actionContext.getXid();
        log.info("[TCC-Try] xid={} prepareSignTransfer: fromPubkey={} toPubkeyHash={} amount={}",
                xid, fromPubkey, toPubkeyHash, amount);

        // 1. 验证平台 keystore（与 TxController.signAndBroadcast 一致）
        String prikey = platformKeystore == null ? null : platformKeystore.getPrikey();
        if (prikey == null || prikey.isBlank()) {
            log.error("[TCC-Try] xid={} No signing key available: platform keystore not configured", xid);
            throw new TccPrepareException("No signing key available: wallet.keystore.json is not configured");
        }
        String platformPubkey = platformKeystore.getPubkey();
        if (platformPubkey == null || platformPubkey.isBlank()
                || !platformPubkey.equalsIgnoreCase(fromPubkey)) {
            log.error("[TCC-Try] xid={} fromPubkey does not match platform keystore public key", xid);
            throw new TccPrepareException("fromPubkey does not match the platform keystore public key");
        }

        // 2. 推导 address
        String frompubhash = WalletUtils.pubkeyStrToPubkeyHashStr(fromPubkey);
        String address = WalletUtils.pubkeyHashToAddress(frompubhash);
        if (WalletUtils.verifyAddress(address) != 0) {
            log.error("[TCC-Try] xid={} Address Error: address={}", xid, address);
            throw new TccPrepareException("Address Error");
        }

        // 3. 获取 nonce（maxNonce 或 RPC getNonce，与 TxController 一致）
        long maxNonce = noncePool.getMaxNonce(address);
        long nonce;
        if (maxNonce == 0) {
            // 池为空，RPC 获取链上 nonce
            JsonObject getNonceResp = nodeController.getNonce(frompubhash);
            int code = getNonceResp != null && getNonceResp.has("code") ? getNonceResp.get("code").getAsInt() : 0;
            if (code == 5000 || getNonceResp == null || !getNonceResp.has("data")) {
                log.error("[TCC-Try] xid={} RPC getNonce failed: frompubhash={}", xid, frompubhash);
                throw new TccPrepareException("RPC getNonce failed");
            }
            nonce = getNonceResp.get("data").getAsLong();
        } else {
            nonce = maxNonce;
        }

        // 4. 预锁定 nonce
        long locked = noncePool.lockNonce(address, nonce);
        if (locked < 0) {
            log.error("[TCC-Try] xid={} lockNonce conflict: address={} nonce={}", xid, address, nonce);
            throw new TccPrepareException("lockNonce conflict: address=" + address + " already locked");
        }

        // 5. 写入 BusinessActionContext 供 Confirm/Cancel 使用
        //    amount 用 String 存储避免 BigDecimal JSON 序列化精度丢失
        actionContext.addActionContext(CTX_FROM_PUBKEY, fromPubkey);
        actionContext.addActionContext(CTX_TO_PUBKEY_HASH, toPubkeyHash);
        actionContext.addActionContext(CTX_AMOUNT, amount.toPlainString());
        actionContext.addActionContext(CTX_NONCE, nonce);
        actionContext.addActionContext(CTX_ADDRESS, address);

        log.info("[TCC-Try] xid={} prepareSignTransfer SUCCESS: address={} nonce={} locked",
                xid, address, nonce);
        return true;
    }

    // ==================== Confirm：签名 + 广播 + 释放 nonce ====================

    @Override
    public boolean confirmSignTransfer(BusinessActionContext actionContext) {
        String xid = actionContext.getXid();
        String fromPubkey = actionContext.getActionContext(CTX_FROM_PUBKEY, String.class);
        String toPubkeyHash = actionContext.getActionContext(CTX_TO_PUBKEY_HASH, String.class);
        String amountStr = actionContext.getActionContext(CTX_AMOUNT, String.class);
        Long nonce = actionContext.getActionContext(CTX_NONCE, Long.class);
        String address = actionContext.getActionContext(CTX_ADDRESS, String.class);

        log.info("[TCC-Confirm] xid={} confirmSignTransfer: address={} nonce={} fromPubkey={} toPubkeyHash={} amount={}",
                xid, address, nonce, fromPubkey, toPubkeyHash, amountStr);

        if (fromPubkey == null || toPubkeyHash == null || amountStr == null || nonce == null || address == null) {
            log.error("[TCC-Confirm] xid={} missing action context (already confirmed?)", xid);
            return true; // 幂等：可能已 Confirm，无上下文视为成功
        }

        BigDecimal amount = new BigDecimal(amountStr);
        String prikey = platformKeystore.getPrikey();

        // 1. 签名（与 TxController.signAndBroadcast 一致）
        ObjectNode data = TxUtils.ClientToTransferAccount(fromPubkey, toPubkeyHash, amount, prikey, nonce);
        if (data == null || data.isEmpty() || !data.has("data")) {
            log.error("[TCC-Confirm] xid={} signing failed: TxUtils returned empty data", xid);
            // 签名失败不抛异常（Confirm 不能触发回滚，只能返回 false 让 TM 重试）
            return false;
        }
        String txHash = data.get("data").asText();
        String traninfo = data.has("message") ? data.get("message").asText() : null;

        // 2. 广播（TxController 现有逻辑未显式广播，TCC Confirm 阶段补上以确保上链）
        if (traninfo != null && !traninfo.isEmpty()) {
            try {
                JsonObject broadcastResp = nodeController.sendTransaction(traninfo);
                int bCode = broadcastResp != null && broadcastResp.has("code")
                        ? broadcastResp.get("code").getAsInt() : 0;
                if (bCode == 2000) {
                    log.info("[TCC-Confirm] xid={} broadcast SUCCESS: txHash={}", xid, txHash);
                } else {
                    // 广播失败不阻塞 Confirm（签名已完成，nonce 已使用；广播可由对账任务重试）
                    log.warn("[TCC-Confirm] xid={} broadcast returned non-2000: code={} txHash={}",
                            xid, bCode, txHash);
                }
            } catch (Exception e) {
                log.warn("[TCC-Confirm] xid={} broadcast exception (signed tx still valid, reconcile later): txHash={}",
                        xid, txHash, e);
            }
        }

        // 3. 释放 nonce 锁定（标记为 USED，nonce+1 写入 pool 作为下一个可用）
        boolean confirmed = noncePool.confirmNonce(address, nonce, txHash);
        if (!confirmed) {
            log.warn("[TCC-Confirm] xid={} confirmNonce returned false (persist error?) address={} nonce={}",
                    xid, address, nonce);
            return false;
        }

        // 4. 把 txHash 写入 context 供调用方查询
        actionContext.addActionContext(CTX_TX_HASH, txHash);

        log.info("[TCC-Confirm] xid={} confirmSignTransfer SUCCESS: address={} nonce={} txHash={}",
                xid, address, nonce, txHash);
        return true;
    }

    // ==================== Cancel：释放 nonce 锁定 ====================

    @Override
    public boolean cancelSignTransfer(BusinessActionContext actionContext) {
        String xid = actionContext.getXid();
        Long nonce = actionContext.getActionContext(CTX_NONCE, Long.class);
        String address = actionContext.getActionContext(CTX_ADDRESS, String.class);

        log.info("[TCC-Cancel] xid={} cancelSignTransfer: address={} nonce={}", xid, address, nonce);

        if (nonce == null || address == null) {
            log.warn("[TCC-Cancel] xid={} missing action context (Try failed before lock? idempotent success)",
                    xid);
            return true; // 幂等：Try 阶段抛异常前未写入 context，Cancel 无 nonce 可释放
        }

        boolean cancelled = noncePool.cancelNonce(address, nonce);
        if (cancelled) {
            log.info("[TCC-Cancel] xid={} cancelSignTransfer SUCCESS: address={} nonce={} released to AVAILABLE",
                    xid, address, nonce);
        } else {
            log.warn("[TCC-Cancel] xid={} cancelSignTransfer idempotent: no lock record for address={} nonce={}",
                    xid, address, nonce);
        }
        // 无论是否释放成功，Cancel 都返回 true（幂等，不触发进一步回滚）
        return true;
    }
}