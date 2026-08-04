package org.nexus.sdk;

import org.nexus.sdk.wallet.TxUtils;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Transaction builder backed by nexus-core TxUtils.
 */
public class TransactionBuilder {

    private final RpcClient rpcClient;
    private final String network;

    public TransactionBuilder(RpcClient rpcClient, String network) {
        this.rpcClient = rpcClient;
        this.network = network;
    }

    public Transaction buildTransfer(String from, String to, BigInteger amount, String token) {
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (from == null || to == null) throw new IllegalArgumentException("From/to addresses required");
        Transaction tx = new Transaction();
        tx.setFrom(from); tx.setTo(to); tx.setValue(amount); tx.setToken(token);
        return tx;
    }

    public Transaction buildContractCall(String from, String contractAddress, String data, BigInteger value) {
        Transaction tx = new Transaction();
        tx.setFrom(from); tx.setTo(contractAddress); tx.setData(data); tx.setValue(value);
        return tx;
    }

    /**
     * Sign a transaction using the legacy Ed25519 signature path via TxUtils.
     * Constructs the raw hex transaction, then signs with the private key.
     */
    public String sign(Transaction tx, String privateKey) {
        BigDecimal amount = new BigDecimal(tx.getValue());
        long nonce = tx.getNonce() != null ? tx.getNonce().longValue() : 0L;
        String rawHex = TxUtils.CreateRawTransaction(tx.getFrom(), tx.getTo(), amount, nonce);
        if (rawHex == null || rawHex.isEmpty())
            throw new RuntimeException("Failed to create raw transaction");
        return TxUtils.signRawBasicTransaction(rawHex, privateKey);
    }

    public String broadcast(String signedTx) {
        Object result = rpcClient.call("nexus_sendRawTransaction", new Object[]{signedTx});
        return result != null ? result.toString() : null;
    }

    public TransactionReceipt getTransactionReceipt(String txHash) {
        Object result = rpcClient.call("nexus_getTransactionReceipt", new Object[]{txHash});
        if (result == null) return null;
        try {
            return rpcClient.call("nexus_getTransactionReceipt", new Object[]{txHash}, TransactionReceipt.class);
        } catch (Exception e) {
            TransactionReceipt r = new TransactionReceipt();
            r.setTransactionHash(txHash);
            r.setStatus("UNKNOWN");
            return r;
        }
    }

    public BigInteger estimateGas(Transaction tx) {
        Object result = rpcClient.call("nexus_estimateGas", new Object[]{tx});
        if (result instanceof Number) return BigInteger.valueOf(((Number) result).longValue());
        return new BigInteger(result.toString());
    }

    public BigInteger getGasPrice() {
        Object result = rpcClient.call("nexus_gasPrice");
        if (result instanceof Number) return BigInteger.valueOf(((Number) result).longValue());
        return new BigInteger(result.toString());
    }

    // --- POJOs ---

    public static class Transaction {
        private String from, to, data, token;
        private BigInteger value, gasLimit, gasPrice, nonce;
        public String getFrom() { return from; } public void setFrom(String s) { from = s; }
        public String getTo() { return to; } public void setTo(String s) { to = s; }
        public BigInteger getValue() { return value; } public void setValue(BigInteger v) { value = v; }
        public BigInteger getGasLimit() { return gasLimit; } public void setGasLimit(BigInteger v) { gasLimit = v; }
        public BigInteger getGasPrice() { return gasPrice; } public void setGasPrice(BigInteger v) { gasPrice = v; }
        public BigInteger getNonce() { return nonce; } public void setNonce(BigInteger v) { nonce = v; }
        public String getData() { return data; } public void setData(String s) { data = s; }
        public String getToken() { return token; } public void setToken(String s) { token = s; }
    }

    public static class TransactionReceipt {
        private String transactionHash, blockHash, status;
        private long blockNumber;
        private BigInteger gasUsed;
        public String getTransactionHash() { return transactionHash; } public void setTransactionHash(String s) { transactionHash = s; }
        public String getBlockHash() { return blockHash; } public void setBlockHash(String s) { blockHash = s; }
        public long getBlockNumber() { return blockNumber; } public void setBlockNumber(long n) { blockNumber = n; }
        public String getStatus() { return status; } public void setStatus(String s) { status = s; }
        public BigInteger getGasUsed() { return gasUsed; } public void setGasUsed(BigInteger v) { gasUsed = v; }
    }
}
