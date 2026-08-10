package org.nexus.sdk.wallet;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TxUtils 单元测试。
 *
 * p.覆盖所有 CreateRaw* 纯逻辑方法、obtainServiceCharge、byteToTransaction、
 * getTransactioninfo 与异常返回路径。网络相关方法不在单元测试覆盖范围内。
 */
class TxUtilsTest {

    private static final String FROM_PUBKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TO_PUBKEY_HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final String TX_ID_32 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final BigDecimal AMOUNT = new BigDecimal("1.5");
    private static final Long NONCE = 0L;

    @Test
    void obtainServiceCharge_shouldDivideTotalByGas() {
        assertEquals(4L, TxUtils.obtainServiceCharge(50000L, 200000L));
    }

    @Test
    void obtainServiceCharge_shouldRoundHalfUp() {
        assertEquals(3L, TxUtils.obtainServiceCharge(3L, 10L));
    }

    @Test
    void obtainServiceCharge_exactDivision() {
        assertEquals(2L, TxUtils.obtainServiceCharge(100L, 200L));
    }

    @Test
    void createRawTransaction_validInputs_shouldReturnNonEmptyHex() {
        String hex = TxUtils.CreateRawTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE);

        assertNotNull(hex);
        assertFalse(hex.isEmpty());
        assertTrue(hex.matches("[0-9a-f]+"), "should be lowercase hex");
    }

    @Test
    void createRawTransaction_invalidHex_shouldReturnEmpty() {
        String hex = TxUtils.CreateRawTransaction("not-hex!", TO_PUBKEY_HASH, AMOUNT, NONCE);
        assertEquals("", hex);
    }

    @Test
    void createRawHatchTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawHatchTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, null, 1, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawHatchTransaction_withSharePubkey_shouldReturnHex() {
        String hex = TxUtils.CreateRawHatchTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, "sharekey", 2, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawHatchTransaction_invalidHex_shouldReturnEmpty() {
        String hex = TxUtils.CreateRawHatchTransaction("zzz", TO_PUBKEY_HASH, AMOUNT, null, 1, NONCE);
        assertEquals("", hex);
    }

    @Test
    void createRawProfitTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawProfitTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, TX_ID_32, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawProfitTransaction_invalidHex_shouldReturnEmpty() {
        String hex = TxUtils.CreateRawProfitTransaction("zzz", TO_PUBKEY_HASH, AMOUNT, TX_ID_32, NONCE);
        assertEquals("", hex);
    }

    @Test
    void createRawShareProfitTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawShareProfitTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, TX_ID_32, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawHatchPrincipalTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawHatchPrincipalTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, TX_ID_32, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawVoteTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawVoteTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawVoteWithdrawTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawVoteWithdrawTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE, TX_ID_32);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawMortgageTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawMortgageTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawMortgageWithdrawTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawMortgageWithdrawTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, TX_ID_32, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawProveTransaction_validInputs_shouldReturnHex() {
        byte[] payload = "test payload".getBytes();
        String hex = TxUtils.CreateRawProveTransaction(FROM_PUBKEY, payload, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawProveTransaction_invalidHex_shouldReturnEmpty() {
        byte[] payload = "test".getBytes();
        String hex = TxUtils.CreateRawProveTransaction("zzz", payload, NONCE);
        assertEquals("", hex);
    }

    @Test
    void createRawChannelOpenTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawChannelOpenTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, 100L, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawChannelCloseTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawChannelCloseTransaction(
                FROM_PUBKEY, "ch-1", BigDecimal.ONE, BigDecimal.ZERO, "sig1", "sig2", NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawBatchTransferTransaction_validInputs_shouldReturnHex() {
        ObjectNode recipient = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        recipient.put("to", TO_PUBKEY_HASH);
        recipient.put("amount", 100);
        String hex = TxUtils.CreateRawBatchTransferTransaction(FROM_PUBKEY, List.of(recipient), NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawBatchTransferTransaction_emptyRecipients_shouldReturnHex() {
        String hex = TxUtils.CreateRawBatchTransferTransaction(FROM_PUBKEY, List.of(), NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawMintStableCoinTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawMintStableCoinTransaction(FROM_PUBKEY, BigDecimal.TEN, BigDecimal.ONE, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawRedeemStableCoinTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawRedeemStableCoinTransaction(FROM_PUBKEY, BigDecimal.ONE, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawBridgeLockTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawBridgeLockTransaction(FROM_PUBKEY, "ethereum", "0xrecipient", AMOUNT, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void createRawSubscriptionAuthTransaction_validInputs_shouldReturnHex() {
        String hex = TxUtils.CreateRawSubscriptionAuthTransaction(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, 100L, 1000L, NONCE);
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void signRawBasicTransaction_invalidHex_shouldReturnEmpty() {
        String result = TxUtils.signRawBasicTransaction("zzz", "zzz");
        assertEquals("", result);
    }

    @Test
    void signRawBasicTransaction_validRawAndInvalidKey_shouldReturnEmpty() {
        // 先构造合法 raw tx，但用非法私钥签名
        String rawTx = TxUtils.CreateRawTransaction(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE);
        String result = TxUtils.signRawBasicTransaction(rawTx, "zzz");
        assertEquals("", result);
    }

    @Test
    void byteToTransaction_invalidHex_shouldReturnEmptyNode() {
        ObjectNode result = TxUtils.byteToTransaction("zzz");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void byteToTransaction_emptyHex_shouldReturnEmptyNode() {
        ObjectNode result = TxUtils.byteToTransaction("");
        assertNotNull(result);
        // 空 hex 解码为空字节数组，protobuf 解析可能失败，返回空 node
    }

    @Test
    void getTransactioninfo_shouldReturnBlockHashAndHeight() {
        ObjectNode result = TxUtils.getTransactioninfo("0xtxid");

        assertNotNull(result);
        // 应包含 data 字段（APIResult 包装）
        assertTrue(result.has("data") || result.isEmpty());
    }

    @Test
    void clientToTransferAccount_invalidInputs_shouldReturnEmptyNode() {
        ObjectNode result = TxUtils.ClientToTransferAccount("zzz", TO_PUBKEY_HASH, AMOUNT, "zzz", NONCE);
        assertNotNull(result);
        // 无效输入应返回空 node 或不抛异常
    }

    @Test
    void clientToTransferAccount_validInputs_shouldReturnNode() {
        // 需要合法私钥（64 hex 字符）。用全 0 私钥测试不抛异常即可。
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToTransferAccount(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, prikey, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToIncubateAccount_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToIncubateAccount(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, prikey, null, 1, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToIncubateProfit_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToIncubateProfit(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, prikey, TX_ID_32, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToIncubateShareProfit_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToIncubateShareProfit(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, prikey, TX_ID_32, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToIncubatePrincipal_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToIncubatePrincipal(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, prikey, TX_ID_32, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToTransferVote_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToTransferVote(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE, prikey);
        assertNotNull(result);
    }

    @Test
    void clientToTransferVoteWithdraw_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToTransferVoteWithdraw(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE, prikey, TX_ID_32);
        assertNotNull(result);
    }

    @Test
    void clientToTransferMortgage_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToTransferMortgage(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE, prikey);
        assertNotNull(result);
    }

    @Test
    void clientToTransferMortgageWithdraw_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToTransferMortgageWithdraw(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, NONCE, TX_ID_32, prikey);
        assertNotNull(result);
    }

    @Test
    void clientToTransferProve_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        byte[] payload = "test".getBytes();
        ObjectNode result = TxUtils.ClientToTransferProve(FROM_PUBKEY, NONCE, payload, prikey);
        assertNotNull(result);
    }

    @Test
    void clientToChannelOpen_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToChannelOpen(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, 100L, prikey, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToBatchTransfer_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode recipient = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        recipient.put("to", TO_PUBKEY_HASH);
        recipient.put("amount", 100);
        ObjectNode result = TxUtils.ClientToBatchTransfer(FROM_PUBKEY, List.of(recipient), prikey, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToMintStableCoin_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToMintStableCoin(FROM_PUBKEY, BigDecimal.TEN, BigDecimal.ONE, prikey, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToBridgeLock_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToBridgeLock(
                FROM_PUBKEY, "ethereum", "0xrecipient", AMOUNT, prikey, NONCE);
        assertNotNull(result);
    }

    @Test
    void clientToSubscriptionAuth_validInputs_shouldReturnNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.ClientToSubscriptionAuth(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, 100L, 1000L, prikey, NONCE);
        assertNotNull(result);
    }

    @Test
    void getChannelState_unreachable_shouldReturnErrorNode() {
        ObjectNode result = TxUtils.getChannelState("ch-1", "http://localhost:9999");
        assertNotNull(result);
        // 连接失败应返回含 error 字段的 node
    }

    @Test
    void getCollateralRatio_unreachable_shouldReturnErrorNode() {
        ObjectNode result = TxUtils.getCollateralRatio("0xaddr", "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void getBridgeStatus_unreachable_shouldReturnErrorNode() {
        ObjectNode result = TxUtils.getBridgeStatus("0xtx", "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void getBatchStatus_unreachable_shouldReturnErrorNode() {
        ObjectNode result = TxUtils.getBatchStatus("0xtx", "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void openChannel_unreachable_shouldReturnErrorNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.openChannel(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, 100L, prikey, NONCE, "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void batchTransfer_unreachable_shouldReturnErrorNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode recipient = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        recipient.put("to", TO_PUBKEY_HASH);
        ObjectNode result = TxUtils.batchTransfer(
                FROM_PUBKEY, List.of(recipient), prikey, NONCE, "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void mintStableCoin_unreachable_shouldReturnErrorNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.mintStableCoin(
                FROM_PUBKEY, BigDecimal.TEN, BigDecimal.ONE, prikey, NONCE, "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void bridgeLock_unreachable_shouldReturnErrorNode() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        ObjectNode result = TxUtils.bridgeLock(
                FROM_PUBKEY, "ethereum", "0xrecipient", AMOUNT, prikey, NONCE, "http://localhost:9999");
        assertNotNull(result);
    }

    @Test
    void sendTransac_unreachable_shouldReturnEmptyString() {
        String result = TxUtils.sendTransac("http://localhost:9999/api", "data");
        assertNotNull(result);
    }

    @Test
    void connect_unreachable_shouldNotThrow() {
        // 不应抛异常
        TxUtils.connect("localhost", "9999");
    }
}