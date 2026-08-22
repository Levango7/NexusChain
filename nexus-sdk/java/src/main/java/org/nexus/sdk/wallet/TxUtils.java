package org.nexus.sdk.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.encoding.BigEndian;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.util.JsonUtils;
import org.nexus.protobuf.tcp.ProtocolModel;
import org.nexus.protobuf.tcp.command.HatchModel;
import org.nexus.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

/**
 * Jackson-based rewrite of the legacy {@code com.company.keystore.wallet.TxUtility}.
 *
 * <p>All fastjson types ({@code com.alibaba.fastjson.JSON}/{@code JSONObject}) are replaced
 * with Jackson equivalents ({@link JsonNode}/{@link ObjectNode}). Crypto/protobuf classes
 * reuse {@code org.nexus.*} from nexus-core, eliminating the nexus-java-sdk / wcli.jar
 * dependency entirely.</p>
 */
public class TxUtils extends Thread {
    private static final Logger log = LoggerFactory.getLogger(TxUtils.class);
    private static final Long rate = 100000000L;
    private static final Long serviceCharge = 200000L;

    // ==================== Raw transaction builders ====================

    /**
     * Construct a raw NEX transfer transaction (hex).
     * fastjson: none used (pure byte manipulation).
     */
    public static String CreateRawTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x01};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] allPayload = BigEndian.encodeUint32(0);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Construct a raw hatch (incubation) transaction.
     * fastjson: none. protobuf HatchModel.Payload reused from nexus-core.
     */
    public static String CreateRawHatchTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String sharepubkeyhash, Integer hatchType, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x09};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(100000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            HatchModel.Payload.Builder payloads = HatchModel.Payload.newBuilder();
            byte[] nullTxid = new byte[32];
            payloads.setTxId(ByteString.copyFrom(nullTxid));
            if (sharepubkeyhash != null) {
                payloads.setSharePubkeyHash(sharepubkeyhash);
            }
            payloads.setType(hatchType);
            byte[] payload = payloads.build().toByteArray();
            byte[] payloadleng = ByteUtil.intToBytes(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawProfitTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String txid, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x0a};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(100000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] payload = Hex.decodeHex(txid.toCharArray());
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawShareProfitTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String txid, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x0b};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(100000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] payload = Hex.decodeHex(txid.toCharArray());
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawHatchPrincipalTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String txid, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x0c};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(100000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] payload = Hex.decodeHex(txid.toCharArray());
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawVoteTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x02};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(20000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] allPayload = BigEndian.encodeUint32(0);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawVoteWithdrawTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce, String txid) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x0d};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(20000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] payload = Hex.decodeHex(txid.toCharArray());
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawMortgageTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x0e};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(20000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] allPayload = BigEndian.encodeUint32(0);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawMortgageWithdrawTransaction(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String txid, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x0f};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(20000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(toPubkeyHashStr.toCharArray());
            byte[] payload = Hex.decodeHex(txid.toCharArray());
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawProveTransaction(String fromPubkeyStr, byte[] payload, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x03};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(100000L, serviceCharge));
            byte[] Amount = ByteUtil.longToBytes(0);
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = new byte[20];
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Construct a raw channel-open transaction.
     * fastjson {@code new JSONObject()}/{@code JSON.toJSONString} → Jackson
     * {@code JsonUtils.createObjectNode()}/{@code JsonUtils.toJson}.
     */
    public static String CreateRawChannelOpenTransaction(String fromPubkeyStr, String counterpartyPubkeyHashStr, BigDecimal fundingAmount, Long lockTimeBlocks, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x10};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            BigDecimal bdAmount = fundingAmount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(counterpartyPubkeyHashStr.toCharArray());
            ObjectNode payloadObj = JsonUtils.createObjectNode();
            payloadObj.put("lockTimeBlocks", lockTimeBlocks);
            byte[] payload = JsonUtils.toJson(payloadObj).getBytes("UTF-8");
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawChannelCloseTransaction(String fromPubkeyStr, String channelId, BigDecimal finalBalance1, BigDecimal finalBalance2, String sig1, String sig2, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x12};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            byte[] Amount = ByteUtil.longToBytes(0);
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = new byte[20];
            ObjectNode payloadObj = JsonUtils.createObjectNode();
            payloadObj.put("channelId", channelId);
            payloadObj.put("finalBalance1", finalBalance1);
            payloadObj.put("finalBalance2", finalBalance2);
            payloadObj.put("sig1", sig1);
            payloadObj.put("sig2", sig2);
            byte[] payload = JsonUtils.toJson(payloadObj).getBytes("UTF-8");
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Construct a raw batch-transfer transaction.
     * fastjson {@code List<JSONObject>} → Jackson {@code List<ObjectNode>}.
     */
    public static String CreateRawBatchTransferTransaction(String fromPubkeyStr, List<ObjectNode> recipients, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x13};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            byte[] Amount = ByteUtil.longToBytes(0);
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = new byte[20];
            ObjectNode payloadObj = JsonUtils.createObjectNode();
            payloadObj.set("recipients", JsonUtils.MAPPER.valueToTree(recipients));
            byte[] payload = JsonUtils.toJson(payloadObj).getBytes("UTF-8");
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawMintStableCoinTransaction(String fromPubkeyStr, BigDecimal collateralAmount, BigDecimal mintAmount, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x14};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            BigDecimal bdAmount = collateralAmount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = new byte[20];
            ObjectNode payloadObj = JsonUtils.createObjectNode();
            payloadObj.put("mintAmount", mintAmount);
            byte[] payload = JsonUtils.toJson(payloadObj).getBytes("UTF-8");
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawRedeemStableCoinTransaction(String fromPubkeyStr, BigDecimal redeemAmount, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x15};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            BigDecimal bdAmount = redeemAmount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = new byte[20];
            byte[] allPayload = BigEndian.encodeUint32(0);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawBridgeLockTransaction(String fromPubkeyStr, String targetChain, String recipient, BigDecimal amount, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x16};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            BigDecimal bdAmount = amount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = new byte[20];
            ObjectNode payloadObj = JsonUtils.createObjectNode();
            payloadObj.put("targetChain", targetChain);
            payloadObj.put("recipient", recipient);
            byte[] payload = JsonUtils.toJson(payloadObj).getBytes("UTF-8");
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    public static String CreateRawSubscriptionAuthTransaction(String fromPubkeyStr, String merchantPubkeyHash, BigDecimal maxAmount, Long intervalBlocks, Long duration, Long nonce) {
        try {
            byte[] version = new byte[]{0x01};
            byte[] type = new byte[]{0x1a};
            byte[] nonece = BigEndian.encodeUint64(nonce + 1);
            byte[] fromPubkeyHash = Hex.decodeHex(fromPubkeyStr.toCharArray());
            byte[] gasPrice = ByteUtil.longToBytes(obtainServiceCharge(50000L, serviceCharge));
            BigDecimal bdAmount = maxAmount.multiply(BigDecimal.valueOf(rate));
            byte[] Amount = ByteUtil.longToBytes(bdAmount.longValue());
            byte[] signull = new byte[64];
            byte[] toPubkeyHash = Hex.decodeHex(merchantPubkeyHash.toCharArray());
            ObjectNode payloadObj = JsonUtils.createObjectNode();
            payloadObj.put("intervalBlocks", intervalBlocks);
            payloadObj.put("duration", duration);
            byte[] payload = JsonUtils.toJson(payloadObj).getBytes("UTF-8");
            byte[] payloadleng = BigEndian.encodeUint32(payload.length);
            byte[] allPayload = ByteUtil.merge(payloadleng, payload);
            byte[] RawTransaction = ByteUtil.merge(version, type, nonece, fromPubkeyHash, gasPrice, Amount, signull, toPubkeyHash, allPayload);
            return new String(Hex.encodeHex(RawTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Signing ====================

    /**
     * Sign a raw transaction hex.
     * No fastjson used (pure byte manipulation + Ed25519 sign).
     */
    public static String signRawBasicTransaction(String RawTransactionHex, String prikeyStr) {
        try {
            byte[] RawTransaction = Hex.decodeHex(RawTransactionHex.toCharArray());
            byte[] privkey = Hex.decodeHex(prikeyStr.toCharArray());
            byte[] version = ByteUtil.bytearraycopy(RawTransaction, 0, 1);
            byte[] type = ByteUtil.bytearraycopy(RawTransaction, 1, 1);
            byte[] nonce = ByteUtil.bytearraycopy(RawTransaction, 2, 8);
            byte[] from = ByteUtil.bytearraycopy(RawTransaction, 10, 32);
            byte[] gasprice = ByteUtil.bytearraycopy(RawTransaction, 42, 8);
            byte[] amount = ByteUtil.bytearraycopy(RawTransaction, 50, 8);
            byte[] signo = ByteUtil.bytearraycopy(RawTransaction, 58, 64);
            byte[] to = ByteUtil.bytearraycopy(RawTransaction, 122, 20);
            byte[] payloadlen = ByteUtil.bytearraycopy(RawTransaction, 142, 4);
            byte[] payload = ByteUtil.bytearraycopy(RawTransaction, 146, (int) BigEndian.decodeUint32(payloadlen));
            byte[] RawTransactionNoSign = ByteUtil.merge(version, type, nonce, from, gasprice, amount, signo, to, payloadlen, payload);
            byte[] RawTransactionNoSig = ByteUtil.merge(version, type, nonce, from, gasprice, amount);
            byte[] sig = new Ed25519PrivateKey(privkey).sign(RawTransactionNoSign);
            byte[] transha = SHA3Utility.keccak256(ByteUtil.merge(RawTransactionNoSig, sig, to, payloadlen, payload));
            byte[] signRawBasicTransaction = ByteUtil.merge(version, transha, type, nonce, from, gasprice, amount, sig, to, payloadlen, payload);
            return new String(Hex.encodeHex(signRawBasicTransaction));
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Signed transaction helpers ====================
    // fastjson JSON.toJSONString(ar) / JSON.parseObject(jsonString) → Jackson JsonUtils.toJson / JsonUtils.readTree
    // Return type JSONObject → ObjectNode

    public static ObjectNode ClientToTransferAccount(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String prikeyStr, Long nonce) {
        try {
            String RawTransactionHex = CreateRawTransaction(fromPubkeyStr, toPubkeyHashStr, amount, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            String jsonString = JsonUtils.toJson(ar);
            return (ObjectNode) JsonUtils.readTree(jsonString);
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToIncubateAccount(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String prikeyStr, String sharepubkeyhash, Integer hatchType, Long nonce) {
        try {
            String RawTransactionHex = CreateRawHatchTransaction(fromPubkeyStr, toPubkeyHashStr, amount, sharepubkeyhash, hatchType, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToIncubateProfit(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String prikeyStr, String txid, Long nonce) {
        try {
            String RawTransactionHex = CreateRawProfitTransaction(fromPubkeyStr, toPubkeyHashStr, amount, txid, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToIncubateShareProfit(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String prikeyStr, String txid, Long nonce) {
        try {
            String RawTransactionHex = CreateRawShareProfitTransaction(fromPubkeyStr, toPubkeyHashStr, amount, txid, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToIncubatePrincipal(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, String prikeyStr, String txid, Long nonce) {
        try {
            String RawTransactionHex = CreateRawHatchPrincipalTransaction(fromPubkeyStr, toPubkeyHashStr, amount, txid, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToTransferVote(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce, String prikeyStr) {
        try {
            String RawTransactionHex = CreateRawVoteTransaction(fromPubkeyStr, toPubkeyHashStr, amount, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToTransferVoteWithdraw(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce, String prikeyStr, String txid) {
        try {
            String RawTransactionHex = CreateRawVoteWithdrawTransaction(fromPubkeyStr, toPubkeyHashStr, amount, nonce, txid);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToTransferMortgage(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce, String prikeyStr) {
        try {
            String RawTransactionHex = CreateRawMortgageTransaction(fromPubkeyStr, toPubkeyHashStr, amount, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToTransferMortgageWithdraw(String fromPubkeyStr, String toPubkeyHashStr, BigDecimal amount, Long nonce, String txid, String prikeyStr) {
        try {
            String RawTransactionHex = CreateRawMortgageWithdrawTransaction(fromPubkeyStr, toPubkeyHashStr, amount, txid, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToTransferProve(String fromPubkeyStr, Long nonce, byte[] payload, String prikeyStr) {
        try {
            String RawTransactionHex = CreateRawProveTransaction(fromPubkeyStr, payload, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToChannelOpen(String fromPubkeyStr, String counterpartyPubkeyHashStr, BigDecimal fundingAmount, Long lockTimeBlocks, String prikeyStr, Long nonce) {
        try {
            String RawTransactionHex = CreateRawChannelOpenTransaction(fromPubkeyStr, counterpartyPubkeyHashStr, fundingAmount, lockTimeBlocks, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToBatchTransfer(String fromPubkeyStr, List<ObjectNode> recipients, String prikeyStr, Long nonce) {
        try {
            String RawTransactionHex = CreateRawBatchTransferTransaction(fromPubkeyStr, recipients, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToMintStableCoin(String fromPubkeyStr, BigDecimal collateralAmount, BigDecimal mintAmount, String prikeyStr, Long nonce) {
        try {
            String RawTransactionHex = CreateRawMintStableCoinTransaction(fromPubkeyStr, collateralAmount, mintAmount, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToBridgeLock(String fromPubkeyStr, String targetChain, String recipient, BigDecimal amount, String prikeyStr, Long nonce) {
        try {
            String RawTransactionHex = CreateRawBridgeLockTransaction(fromPubkeyStr, targetChain, recipient, amount, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    public static ObjectNode ClientToSubscriptionAuth(String fromPubkeyStr, String merchantPubkeyHash, BigDecimal maxAmount, Long intervalBlocks, Long duration, String prikeyStr, Long nonce) {
        try {
            String RawTransactionHex = CreateRawSubscriptionAuthTransaction(fromPubkeyStr, merchantPubkeyHash, maxAmount, intervalBlocks, duration, nonce);
            byte[] signRawBasicTransaction = Hex.decodeHex(signRawBasicTransaction(RawTransactionHex, prikeyStr).toCharArray());
            byte[] hash = ByteUtil.bytearraycopy(signRawBasicTransaction, 1, 32);
            String txHash = new String(Hex.encodeHex(hash));
            String traninfo = new String(Hex.encodeHex(signRawBasicTransaction));
            APIResult ar = new APIResult();
            ar.setData(txHash);
            ar.setMessage(traninfo);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ar));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    /**
     * Parse a transaction hex into a JSON result.
     * fastjson → Jackson. Reuses nexus-core Transaction.changeProtobuf/fromProto.
     */
    public static ObjectNode byteToTransaction(String transactionHexStr) {
        try {
            byte[] transaction = Hex.decodeHex(transactionHexStr.toCharArray());
            ProtocolModel.Transaction tranproto = Transaction.changeProtobuf(transaction);
            Transaction tran = Transaction.fromProto(tranproto);
            APIResult apiResult = new APIResult();
            apiResult.setData(tran);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(apiResult));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    /**
     * Get transaction info (block hash + height) by txid.
     * fastjson → Jackson. Returns empty placeholder (legacy behavior).
     */
    public static ObjectNode getTransactioninfo(String txid) {
        try {
            APIResult apiResult = new APIResult();
            ObjectNode dataresult = JsonUtils.createObjectNode();
            dataresult.put("blockHash", "");
            dataresult.put("height", "");
            apiResult.setData(dataresult);
            return (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(apiResult));
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    /**
     * Send a transaction via HTTP POST (legacy best-effort).
     * No fastjson used.
     */
    public static String sendTransac(String path, String data) {
        String str = "";
        try {
            URL url = new URL(path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            PrintWriter out;
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1)");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            out = new PrintWriter(conn.getOutputStream());
            out.print(data);
            out.flush();
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            while ((str = br.readLine()) != null) {
                log.debug("{}", str);
            }
            is.close();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return str;
    }

    /**
     * Calculate gas unit price.
     */
    public static Long obtainServiceCharge(Long gas, Long total) {
        BigDecimal a = new BigDecimal(gas.toString());
        BigDecimal b = new BigDecimal(total.toString());
        BigDecimal divide = b.divide(a, 0, RoundingMode.HALF_UP);
        return divide.longValue();
    }

    // ==================== HTTP helpers ====================
    // fastjson JSON.parseObject → Jackson JsonUtils.readTree
    // JSONObject → ObjectNode

    /**
     * HTTP POST JSON helper.
     * fastjson {@code JSON.parseObject} → Jackson {@code JsonUtils.readTree}.
     */
    private static ObjectNode httpPostJson(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("User-Agent", "NexusChain-Java-SDK/1.0");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes("UTF-8");
                os.write(input);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            InputStream is;
            if (responseCode >= 200 && responseCode < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
                if (is == null) {
                    is = conn.getInputStream();
                }
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            String responseStr = response.toString();
            if (responseStr == null || responseStr.isEmpty()) {
                return JsonUtils.createObjectNode();
            }
            return (ObjectNode) JsonUtils.readTree(responseStr);
        } catch (Exception e) {
            ObjectNode errorResult = JsonUtils.createObjectNode();
            errorResult.put("error", e.getMessage());
            errorResult.put("success", false);
            return errorResult;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * HTTP GET JSON helper.
     * fastjson {@code JSON.parseObject} → Jackson {@code JsonUtils.readTree}.
     */
    private static ObjectNode httpGetJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("User-Agent", "NexusChain-Java-SDK/1.0");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            int responseCode = conn.getResponseCode();
            InputStream is;
            if (responseCode >= 200 && responseCode < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
                if (is == null) {
                    is = conn.getInputStream();
                }
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            String responseStr = response.toString();
            if (responseStr == null || responseStr.isEmpty()) {
                return JsonUtils.createObjectNode();
            }
            return (ObjectNode) JsonUtils.readTree(responseStr);
        } catch (Exception e) {
            ObjectNode errorResult = JsonUtils.createObjectNode();
            errorResult.put("error", e.getMessage());
            errorResult.put("success", false);
            return errorResult;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ==================== End-to-end RPC methods ====================
    // fastjson JSONObject → Jackson ObjectNode
    // signedTx.getString("data") → signedTx.get("data").asText()

    public static ObjectNode openChannel(String fromPubkey, String toPubkeyHash, BigDecimal amount,
                                         Long lockTime, String prikey, Long nonce, String nodeUrl) {
        try {
            ObjectNode signedTx = ClientToChannelOpen(fromPubkey, toPubkeyHash, amount, lockTime, prikey, nonce);
            if (signedTx == null || signedTx.isEmpty()) {
                ObjectNode error = JsonUtils.createObjectNode();
                error.put("error", "构造签名交易失败");
                error.put("success", false);
                return error;
            }
            ObjectNode requestBody = JsonUtils.createObjectNode();
            requestBody.put("txHash", signedTx.get("data").asText());
            requestBody.put("traninfo", signedTx.get("message").asText());
            requestBody.put("fromPubkey", fromPubkey);
            requestBody.put("toPubkeyHash", toPubkeyHash);
            requestBody.put("amount", amount);
            requestBody.put("lockTime", lockTime);
            requestBody.put("nonce", nonce);

            String url = nodeUrl.replaceAll("/+$", "") + "/channel/open";
            return httpPostJson(url, requestBody.toString());
        } catch (Exception e) {
            ObjectNode error = JsonUtils.createObjectNode();
            error.put("error", e.getMessage());
            error.put("success", false);
            return error;
        }
    }

    public static ObjectNode batchTransfer(String fromPubkey, List<ObjectNode> recipients,
                                           String prikey, Long nonce, String nodeUrl) {
        try {
            ObjectNode signedTx = ClientToBatchTransfer(fromPubkey, recipients, prikey, nonce);
            if (signedTx == null || signedTx.isEmpty()) {
                ObjectNode error = JsonUtils.createObjectNode();
                error.put("error", "构造签名交易失败");
                error.put("success", false);
                return error;
            }
            ObjectNode requestBody = JsonUtils.createObjectNode();
            requestBody.put("txHash", signedTx.get("data").asText());
            requestBody.put("traninfo", signedTx.get("message").asText());
            requestBody.put("fromPubkey", fromPubkey);
            requestBody.set("recipients", JsonUtils.MAPPER.valueToTree(recipients));
            requestBody.put("nonce", nonce);

            String url = nodeUrl.replaceAll("/+$", "") + "/batch/transfer";
            return httpPostJson(url, requestBody.toString());
        } catch (Exception e) {
            ObjectNode error = JsonUtils.createObjectNode();
            error.put("error", e.getMessage());
            error.put("success", false);
            return error;
        }
    }

    public static ObjectNode mintStableCoin(String fromPubkey, BigDecimal collateral, BigDecimal mintAmount,
                                            String prikey, Long nonce, String nodeUrl) {
        try {
            ObjectNode signedTx = ClientToMintStableCoin(fromPubkey, collateral, mintAmount, prikey, nonce);
            if (signedTx == null || signedTx.isEmpty()) {
                ObjectNode error = JsonUtils.createObjectNode();
                error.put("error", "构造签名交易失败");
                error.put("success", false);
                return error;
            }
            ObjectNode requestBody = JsonUtils.createObjectNode();
            requestBody.put("txHash", signedTx.get("data").asText());
            requestBody.put("traninfo", signedTx.get("message").asText());
            requestBody.put("fromPubkey", fromPubkey);
            requestBody.put("collateral", collateral);
            requestBody.put("mintAmount", mintAmount);
            requestBody.put("nonce", nonce);

            String url = nodeUrl.replaceAll("/+$", "") + "/stablecoin/mint";
            return httpPostJson(url, requestBody.toString());
        } catch (Exception e) {
            ObjectNode error = JsonUtils.createObjectNode();
            error.put("error", e.getMessage());
            error.put("success", false);
            return error;
        }
    }

    public static ObjectNode bridgeLock(String fromPubkey, String targetChain, String recipient,
                                        BigDecimal amount, String prikey, Long nonce, String nodeUrl) {
        try {
            ObjectNode signedTx = ClientToBridgeLock(fromPubkey, targetChain, recipient, amount, prikey, nonce);
            if (signedTx == null || signedTx.isEmpty()) {
                ObjectNode error = JsonUtils.createObjectNode();
                error.put("error", "构造签名交易失败");
                error.put("success", false);
                return error;
            }
            ObjectNode requestBody = JsonUtils.createObjectNode();
            requestBody.put("txHash", signedTx.get("data").asText());
            requestBody.put("traninfo", signedTx.get("message").asText());
            requestBody.put("fromPubkey", fromPubkey);
            requestBody.put("targetChain", targetChain);
            requestBody.put("recipient", recipient);
            requestBody.put("amount", amount);
            requestBody.put("nonce", nonce);

            String url = nodeUrl.replaceAll("/+$", "") + "/bridge/lock";
            return httpPostJson(url, requestBody.toString());
        } catch (Exception e) {
            ObjectNode error = JsonUtils.createObjectNode();
            error.put("error", e.getMessage());
            error.put("success", false);
            return error;
        }
    }

    public static ObjectNode getChannelState(String channelId, String nodeUrl) {
        String url = nodeUrl.replaceAll("/+$", "") + "/channel/state/" + channelId;
        return httpGetJson(url);
    }

    public static ObjectNode getCollateralRatio(String address, String nodeUrl) {
        String url = nodeUrl.replaceAll("/+$", "") + "/stablecoin/collateral/" + address;
        return httpGetJson(url);
    }

    public static ObjectNode getBridgeStatus(String txHash, String nodeUrl) {
        String url = nodeUrl.replaceAll("/+$", "") + "/bridge/status/" + txHash;
        return httpGetJson(url);
    }

    public static ObjectNode getBatchStatus(String txHash, String nodeUrl) {
        String url = nodeUrl.replaceAll("/+$", "") + "/batch/status/" + txHash;
        return httpGetJson(url);
    }

    /**
     * Legacy connect helper (replaces commons-httpclient 3.x with java.net.HttpURLConnection).
     */
    public static void connect(String ip, String port) {
        try {
            String urlStr = "http://" + ip + ":" + port;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String res;
                    while ((res = br.readLine()) != null) {
                        log.debug("{}", res);
                    }
                }
            }
            conn.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Legacy test helper (debugging).
     */
    public static void test(String path, String data) throws IOException {
        LocalDateTime beginTime = LocalDateTime.now();
        URL url = new URL(path);
        String str;
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        PrintWriter out;
        conn.setRequestProperty("accept", "*/*");
        conn.setRequestProperty("connection", "Keep-Alive");
        conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1)");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        out = new PrintWriter(conn.getOutputStream());
        out.print(data);
        out.flush();
        InputStream is = conn.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        while ((str = br.readLine()) != null) {
            log.debug("{}", str);
            Long timeConsuming = Duration.between(beginTime, LocalDateTime.now()).toMillis();
            log.debug("{}", timeConsuming);
        }
        is.close();
        conn.disconnect();
    }

    @SuppressWarnings("unchecked")
    static class MyCallable implements Callable {
        private String str;

        MyCallable(String path, String data) {
            String str = "";
            try {
                URL url = new URL(path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                PrintWriter out;
                conn.setRequestProperty("accept", "*/*");
                conn.setRequestProperty("connection", "Keep-Alive");
                conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1)");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                out = new PrintWriter(conn.getOutputStream());
                out.print(data);
                out.flush();
                InputStream is = conn.getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                while ((str = br.readLine()) != null) {
                    this.str = str;
                }
                is.close();
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public Object call() throws Exception {
            return str;
        }
    }
}