package org.nexus.controller;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.service.CommandService;
import org.nexus.sync.TransactionHandler;
import org.nexus.core.account.AccountDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
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

    private static final Logger log = LoggerFactory.getLogger(PaymentRpcController.class);

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private CommandService commandService;

    @Autowired
    private TransactionHandler transactionHandler;

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
     * Broadcasts a signed transaction hex to the network.
     * Body: { "signed_tx_hex": "..." }
     */
    @PostMapping("/transaction/broadcast")
    public Map<String, Object> broadcastTransaction(@RequestBody Map<String, String> body) {
        String txHex = body.get("signed_tx_hex");
        if (txHex == null || txHex.isBlank()) {
            return rpcResult(4001, "missing signed_tx_hex", null);
        }
        try {
            byte[] raw = Hex.decodeHex(txHex.toCharArray());
            APIResult result = commandService.verifyTransfer(raw);
            if (result.getCode() == 2000) {
                Transaction t = (Transaction) result.getData();
                transactionHandler.broadcastTransactions(Collections.singletonList(t));
                String txHash = Hex.encodeHexString(t.getHash());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tx_hash", txHash);
                data.put("accepted", true);
                return rpcResult(2000, "transaction broadcast accepted", data);
            }
            return rpcResult(result.getCode(), "transaction rejected: " + result.getMessage(), null);
        } catch (DecoderException e) {
            return rpcResult(4002, "invalid hex encoding: " + e.getMessage(), null);
        } catch (Exception e) {
            log.error("Broadcast failed: {}", e.getMessage());
            return rpcResult(5001, "broadcast error: " + e.getMessage(), null);
        }
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
}