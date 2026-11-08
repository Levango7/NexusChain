package org.nexus.controller;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.core.account.AccountDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standardized Payment RPC Controller for Gateway integration.
 * Provides a clean, versioned API surface for the Payment Orchestration layer.
 * All endpoints return consistent JSON: { "code": int, "message": string, "data": object }
 */
@RestController
@RequestMapping("/rpc/v1")
public class PaymentRpcController {

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private AccountDB accountDB;

    /**
     * GET /rpc/v1/height
     * Returns current chain height.
     */
    @GetMapping("/height")
    public Map<String, Object> getHeight() {
        Block current = bc.currentHeader();
        long height = current != null ? current.nHeight : 0;
        return rpcResult(2000, "success", height);
    }

    /**
     * POST /rpc/v1/transaction/broadcast
     * 支付扩展（0x10–0x1a）保留未启用：本端点不再转发任何交易入池。
     *
     * <p>保留路由与方法签名；空 {@code signed_tx_hex} 优先返回 4001，
     * 其余请求统一返回 HTTP 501 + code 5010。</p>
     *
     * <p>Body: { "signed_tx_hex": "..." }</p>
     */
    @PostMapping("/transaction/broadcast")
    public Object broadcastTransaction(@RequestBody(required = false) Map<String, String> body) {
        String txHex = body == null ? null : body.get("signed_tx_hex");
        if (txHex == null || txHex.isBlank()) {
            return rpcResult(4001, "missing signed_tx_hex", null);
        }
        // 支付扩展（0x10–0x1a）保留未启用：写端点统一拒绝。
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(rpcResult(APIResult.PAYMENT_EXTENSION_NOT_ENABLED,
                        APIResult.PAYMENT_EXTENSION_NOT_ENABLED_MESSAGE, null));
    }

    /**
     * GET /rpc/v1/transaction/{txHash}/status
     * Returns confirmation status of a transaction.
     */
    @GetMapping("/transaction/{txHash}/status")
    public Map<String, Object> getTransactionStatus(@PathVariable String txHash) {
        try {
            byte[] hashBytes = Hex.decodeHex(txHash.toCharArray());
            Transaction tx = bc.getTransaction(hashBytes);
            Block current = bc.currentHeader();
            long currentHeight = current != null ? current.nHeight : 0;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tx_hash", txHash);

            if (tx == null) {
                data.put("status", "NOT_FOUND");
                data.put("confirmations", 0);
                return rpcResult(2000, "transaction not found", data);
            }

            long txHeight = tx.height;
            long confirmations = currentHeight - txHeight;
            String status = confirmations >= 1 ? "CONFIRMED" : "PENDING";

            data.put("status", status);
            data.put("confirmations", Math.max(confirmations, 0));
            data.put("block_height", txHeight);
            return rpcResult(2000, "success", data);
        } catch (DecoderException e) {
            return rpcResult(4002, "invalid tx hash hex", null);
        } catch (RuntimeException e) {
            return rpcResult(5001, "query error: " + e.getMessage(), null);
        }
    }

    /**
     * GET /rpc/v1/transaction/{txHash}
     * Returns full transaction details for the gateway payment-binding check.
     *
     * <p>修复（P0-5 审计）：gateway 的 ChainRpcClient.getTransaction 此前调用本路径，
     * 但 core 侧仅有根路径 /transaction/{txHash}（CommandController）与 /rpc/v1 下的
     * /transaction/{txHash}/status，导致该调用恒 404、v2.27.0 引入的交易-订单绑定校验
     * 永远走降级分支。本端点补齐 /rpc/v1 版本化路径。</p>
     *
     * <p>字段约定与 gateway 解析器（ChainRpcClient#getTransaction）严格对齐：
     * sender/recipient 为 20 字节公钥哈希的<b>小写 hex</b>——与 nexus-sdk
     * WalletUtils.addressToPubkeyHash 的输出同一编码空间，使 gateway 侧
     * "订单收款地址 → 公钥哈希" 与 "链上 to 字段" 的比较具有构造性对称性，
     * 不依赖任何地址编码实现细节。</p>
     */
    @GetMapping("/transaction/{txHash}")
    public Map<String, Object> getTransactionDetail(@PathVariable String txHash) {
        try {
            byte[] hashBytes = Hex.decodeHex(txHash.toCharArray());
            Transaction tx = bc.getTransaction(hashBytes);
            if (tx == null) {
                return rpcResult(4004, "transaction not found", null);
            }
            Block current = bc.currentHeader();
            long currentHeight = current != null ? current.nHeight : 0;
            long confirmations = currentHeight - tx.height;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tx_hash", txHash);
            data.put("amount", tx.amount);
            data.put("sender", Hex.encodeHexString(tx.from));
            data.put("recipient", Hex.encodeHexString(tx.to));
            data.put("confirmed", confirmations >= 1);
            data.put("block_height", tx.height);
            data.put("type", tx.type);
            return rpcResult(2000, "success", data);
        } catch (DecoderException e) {
            return rpcResult(4002, "invalid tx hash hex", null);
        } catch (RuntimeException e) {
            return rpcResult(5001, "query error: " + e.getMessage(), null);
        }
    }

    /**
     * GET /rpc/v1/account/{pubKeyHash}/nonce
     * Returns next nonce for an account (by public key hash hex).
     */
    @GetMapping("/account/{pubKeyHash}/nonce")
    public Map<String, Object> getNonce(@PathVariable String pubKeyHash) {
        try {
            byte[] pkhBytes = Hex.decodeHex(pubKeyHash.toCharArray());
            var accountOpt = accountDB.hasAccount(pkhBytes);
            long nonce = accountOpt.map(a -> (long) a.getNonce()).orElse(0L);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pub_key_hash", pubKeyHash);
            data.put("nonce", nonce);
            return rpcResult(2000, "success", data);
        } catch (DecoderException e) {
            return rpcResult(4002, "invalid pubKeyHash hex", null);
        } catch (RuntimeException e) {
            return rpcResult(5001, "nonce query error: " + e.getMessage(), null);
        }
    }

    // === Helper ===

    private Map<String, Object> rpcResult(int code, String message, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("message", message);
        result.put("data", data);
        return result;
    }

    /**
     * NexFinality 最终性层装配（可选）。
     * 最终性层启用时（nexus.finality 相关组件装配）查询 BFT 投票权重进度；
     * 未装配时返回 FINALITY_NOT_ACTIVE，gateway 应降级为确认数驱动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.nexus.consensus.finality.FinalityGadget finalityGadget;

    /**
     * GET /rpc/v1/finality/epoch/{epoch}
     * 查询指定 epoch 的最终性进度（BFT 质押权重驱动）。
     *
     * <p>返回：</p>
     * <pre>
     * { "finality_status": "FINALIZED|FINALIZING|OPTIMISTIC|UNKNOWN",
     *   "voted_weight": 900, "total_weight": 900, "progress_percent": 100 }
     * </pre>
     */
    @GetMapping("/finality/epoch/{epoch}")
    public Map<String, Object> getEpochFinality(@PathVariable("epoch") long epoch) {
        if (finalityGadget == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("finality_status", "NOT_ACTIVE");
            return rpcResult(2000, "finality layer not active", data);
        }
        try {
            org.nexus.consensus.finality.FinalityRecord rec = finalityGadget.getEpochProgress(epoch);
            Map<String, Object> data = new LinkedHashMap<>();
            if (rec == null) {
                data.put("epoch", epoch);
                data.put("finality_status", "UNKNOWN");
                data.put("voted_weight", 0);
                data.put("total_weight", rec == null ? 0 : rec.getTotalWeight().longValue());
                data.put("progress_percent", 0);
            } else {
                data.put("epoch", epoch);
                data.put("finality_status", rec.isFinalized() ? "FINALIZED"
                        : rec.progressPercent() >= 50 ? "FINALIZING" : "OPTIMISTIC");
                data.put("voted_weight", rec.getVotedWeight().longValue());
                data.put("total_weight", rec.getTotalWeight().longValue());
                data.put("progress_percent", rec.progressPercent());
            }
            return rpcResult(2000, "success", data);
        } catch (RuntimeException e) {
            return rpcResult(5001, "finality query error: " + e.getMessage(), null);
        }
    }
}