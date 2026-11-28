/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */
package org.nexus.service;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.db.StateDB;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real BATCH_TRANSFER signing, admission, and confirmed-state queries. */
@Component
public class BatchTransferService {

    private static final int MAX_RECIPIENTS = 100;
    private static final long MAX_TOTAL_AMOUNT = 100_000_000_000L;

    @Autowired
    CommandService commandService;

    @Autowired
    StateCommitment stateCommitment;

    @Autowired
    StateDB stateDB;

    @Autowired
    NexusChainBlockChain nexusBlockChain;

    /** Build and sign a BATCH transaction locally, then submit it for real admission. */
    public APIResult batchTransfer(String fromPubkey, List<TransferItem> recipients,
                                   String prikey, long nonce) {
        APIResult validation = validateSubmission(fromPubkey, recipients, nonce);
        if (validation != null) {
            return validation;
        }
        if (prikey == null || prikey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
        }
        if (!isActiveForAdmission()) {
            return APIResult.paymentExtensionNotEnabled();
        }
        try {
            byte[] from = Hex.decodeHex(fromPubkey.toCharArray());
            byte[] privateKey = Hex.decodeHex(prikey.toCharArray());
            if (from.length != Transaction.PUBLIC_KEY_SIZE || privateKey.length != 32) {
                return APIResult.newFailResult(APIResult.FAIL, "invalid key length");
            }
            Transaction tx = Transaction.createEmpty();
            tx.type = Transaction.Type.BATCH_TRANSFER.ordinal();
            tx.nonce = Math.addExact(nonce, 1L);
            tx.from = from;
            tx.gasPrice = 1L;
            tx.amount = BatchTransferPayload.getTotalAmount(recipients);
            tx.payload = BatchTransferPayload.build(recipients);
            tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
            tx.signature = new Ed25519PrivateKey(privateKey).sign(tx.getRawForSign());
            return submitSignedBatch(fromPubkey, recipients,
                    Hex.encodeHexString(tx.toRPCBytes()), nonce);
        } catch (RuntimeException | DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to build batch transfer: " + e.getMessage());
        }
    }

    /** Submit an SDK-signed BATCH transaction without transmitting the private key. */
    public APIResult submitSignedBatch(String fromPubkey, List<TransferItem> recipients,
                                       String traninfo, long nonce) {
        APIResult validation = validateSubmission(fromPubkey, recipients, nonce);
        if (validation != null) {
            return validation;
        }
        if (traninfo == null || traninfo.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "traninfo is required");
        }
        if (!isActiveForAdmission()) {
            return APIResult.paymentExtensionNotEnabled();
        }
        try {
            for (TransferItem item : recipients) {
                if (KeystoreAction.verifyAddress(item.getAddress()) != 0) {
                    return APIResult.newFailResult(APIResult.FAIL, "invalid recipient address");
                }
            }
            byte[] raw = Hex.decodeHex(traninfo.toCharArray());
            Transaction tx = Transaction.fromRPCBytes(raw);
            if (tx.type != Transaction.Type.BATCH_TRANSFER.ordinal()) {
                return APIResult.newFailResult(APIResult.FAIL, "transaction is not BATCH_TRANSFER");
            }
            if (!Arrays.equals(tx.from, Hex.decodeHex(fromPubkey.toCharArray()))) {
                return APIResult.newFailResult(APIResult.FAIL, "signed sender does not match fromPubkey");
            }
            if (tx.nonce != Math.addExact(nonce, 1L)) {
                return APIResult.newFailResult(APIResult.FAIL, "signed nonce does not match request");
            }
            if (tx.amount != BatchTransferPayload.getTotalAmount(recipients)
                    || !Arrays.equals(tx.payload, BatchTransferPayload.build(recipients))) {
                return APIResult.newFailResult(APIResult.FAIL, "signed recipients or amount do not match request");
            }
            if (!Arrays.equals(tx.to, new byte[Transaction.PUBLIC_KEY_HASH_SIZE])) {
                return APIResult.newFailResult(APIResult.FAIL, "batch transfer to field must be empty");
            }
            if (commandService == null) {
                return APIResult.newFailResult(APIResult.FAIL, "admission service is unavailable");
            }
            APIResult accepted = commandService.verifyTransfer(raw);
            if (accepted == null) {
                return APIResult.newFailResult(APIResult.FAIL, "admission returned no result");
            }
            if (accepted.getCode() != APIResult.SUCCESS) {
                return accepted;
            }
            APIResult result = APIResult.newSuccess(submissionData(tx));
            result.setMessage("SUCCESS");
            return result;
        } catch (RuntimeException | DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to submit batch transfer: " + e.getMessage());
        }
    }

    /** Read a confirmed BATCH transaction and its canonical effects from the chain. */
    public APIResult getBatchStatus(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "txHash is required");
        }
        if (!isActiveForAdmission()) {
            return APIResult.paymentExtensionNotEnabled();
        }
        try {
            String normalized = txHash.startsWith("0x") ? txHash.substring(2) : txHash;
            if (normalized.length() != 64) {
                return APIResult.newFailResult(APIResult.FAIL, "txHash must contain 32 bytes");
            }
            if (nexusBlockChain == null) {
                return APIResult.newFailResult(APIResult.FAIL, "chain query service is unavailable");
            }
            Transaction tx = nexusBlockChain.getTransaction(Hex.decodeHex(normalized.toCharArray()));
            if (tx == null) {
                return APIResult.newFailResult(APIResult.FAIL, "Batch transfer not found");
            }
            if (tx.type != Transaction.Type.BATCH_TRANSFER.ordinal()) {
                return APIResult.newFailResult(APIResult.FAIL, "transaction is not BATCH_TRANSFER");
            }
            if (!stateCommitment.isV3Active(tx.height)) {
                return APIResult.paymentExtensionNotEnabled();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "CONFIRMED");
            data.put("height", tx.height);
            data.put("txHash", tx.getHashHexString());
            data.put("totalAmount", tx.amount);
            data.put("fee", tx.getFeeExact());
            data.put("nonce", tx.nonce);
            data.put("items", BatchTransferPayload.parse(tx.payload));
            APIResult result = APIResult.newSuccess(data);
            result.setMessage("SUCCESS");
            return result;
        } catch (RuntimeException | DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to query batch transfer status: " + e.getMessage());
        }
    }

    private APIResult validateSubmission(String fromPubkey, List<TransferItem> recipients, long nonce) {
        if (fromPubkey == null || fromPubkey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
        }
        if (recipients == null || recipients.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "recipients must not be empty");
        }
        if (nonce < 0) {
            return APIResult.newFailResult(APIResult.FAIL, "nonce must be non-negative");
        }
        try {
            BatchTransferPayload.validate(recipients, MAX_RECIPIENTS, MAX_TOTAL_AMOUNT);
            return null;
        } catch (IllegalArgumentException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Validation failed: " + e.getMessage());
        }
    }

    private boolean isActiveForAdmission() {
        if (stateCommitment == null) {
            return false;
        }
        long nextHeight = 0;
        if (stateDB != null) {
            Block best = stateDB.getBestBlock();
            if (best != null) {
                nextHeight = Math.addExact(best.nHeight, 1L);
            }
        }
        return stateCommitment.isV3Active(nextHeight);
    }

    private static Map<String, Object> submissionData(Transaction tx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("txHash", tx.getHashHexString());
        data.put("traninfo", Hex.encodeHexString(tx.toRPCBytes()));
        data.put("totalAmount", tx.amount);
        data.put("nonce", tx.nonce);
        data.put("fee", tx.getFeeExact());
        return data;
    }
}
