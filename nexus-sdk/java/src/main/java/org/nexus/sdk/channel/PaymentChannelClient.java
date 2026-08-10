package org.nexus.sdk.channel;

import org.nexus.sdk.RpcClient;
import org.nexus.sdk.TransactionBuilder;

import java.math.BigInteger;

/**
 * 支付通道客户端。
 *
 * <p>提供 NexusChain 支付通道的开启、关闭、链下状态更新和结算能力。
 * 支付通道允许链下高频微支付，最终在链上结算。</p>
 *
 * <p>写操作通过 {@link RpcClient} 调用支付通道合约 RPC 端点；
 * 通道 ID 由服务端生成并经 RPC 返回。</p>
 */
public class PaymentChannelClient {

    /** 支付通道合约默认地址。 */
    private static final String DEFAULT_CHANNEL_CONTRACT = "0x000000000000000000000000000000000000c0de";

    private final RpcClient rpcClient;
    private final String channelContract;

    public PaymentChannelClient(RpcClient rpcClient) {
        this(rpcClient, DEFAULT_CHANNEL_CONTRACT);
    }

    public PaymentChannelClient(RpcClient rpcClient, String channelContract) {
        if (rpcClient == null) {
            throw new IllegalArgumentException("rpcClient is required");
        }
        this.rpcClient = rpcClient;
        this.channelContract = channelContract != null ? channelContract : DEFAULT_CHANNEL_CONTRACT;
    }

    /**
     * 开启支付通道。
     *
     * @param sender    发送方地址
     * @param recipient 接收方地址
     * @param deposit   通道质押金额（NEX，最小单位 wei）
     * @return 通道 ID
     */
    public String openChannel(String sender, String recipient, BigInteger deposit) {
        requireNonEmpty(sender, "sender");
        requireNonEmpty(recipient, "recipient");
        requirePositive(deposit, "deposit");

        Object result = rpcClient.call("nexus_callContract",
                new Object[]{channelContract, "open", new Object[]{sender, recipient, deposit}, "wasm"});
        return extractReturnValue(result);
    }

    /**
     * 关闭支付通道并结算。
     *
     * @param channelId 通道 ID
     * @return 结算交易哈希
     */
    public String closeChannel(String channelId) {
        requireNonEmpty(channelId, "channelId");
        Object result = rpcClient.call("nexus_callContract",
                new Object[]{channelContract, "close", new Object[]{channelId}, "wasm"});
        return extractReturnValue(result);
    }

    /**
     * 更新通道链下状态（签名后的余额证明）。
     *
     * @param channelId   通道 ID
     * @param balanceProof 余额证明（包含签名）
     * @return 是否成功
     */
    public boolean updateChannelState(String channelId, BalanceProof balanceProof) {
        requireNonEmpty(channelId, "channelId");
        if (balanceProof == null) {
            throw new IllegalArgumentException("balanceProof is required");
        }
        if (balanceProof.getBalance() == null || balanceProof.getBalance().signum() < 0) {
            throw new IllegalArgumentException("balanceProof.balance must be non-negative");
        }
        requireNonEmpty(balanceProof.getSignature(), "balanceProof.signature");

        Object result = rpcClient.call("nexus_callContract",
                new Object[]{channelContract, "updateState",
                        new Object[]{channelId, balanceProof.getBalance(),
                                balanceProof.getNonce(), balanceProof.getSignature()}, "wasm"});
        String returnValue = extractReturnValue(result);
        return returnValue != null && !returnValue.isEmpty();
    }

    /**
     * 查询通道信息。
     *
     * @param channelId 通道 ID
     * @return 通道信息
     */
    public ChannelInfo getChannelInfo(String channelId) {
        requireNonEmpty(channelId, "channelId");
        Object result = rpcClient.call("nexus_queryContract",
                new Object[]{channelContract, "getChannel", new Object[]{channelId}, "wasm"});
        if (result instanceof com.fasterxml.jackson.databind.JsonNode) {
            com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) result;
            String sender = node.has("sender") ? node.get("sender").asText() : null;
            String recipient = node.has("recipient") ? node.get("recipient").asText() : null;
            BigInteger deposit = node.has("deposit")
                    ? new BigInteger(node.get("deposit").asText()) : BigInteger.ZERO;
            String status = node.has("status") ? node.get("status").asText() : "UNKNOWN";
            long openBlock = node.has("openBlock") ? node.get("openBlock").asLong() : 0L;
            return new ChannelInfo(channelId, sender, recipient, deposit, status, openBlock);
        }
        return new ChannelInfo(channelId, null, null, BigInteger.ZERO, "UNKNOWN", 0L);
    }

    /**
     * 发起通道争议（挑战对方提交的过期状态）。
     *
     * @param channelId 通道 ID
     * @return 争议交易哈希
     */
    public String challengeChannel(String channelId) {
        requireNonEmpty(channelId, "channelId");
        Object result = rpcClient.call("nexus_callContract",
                new Object[]{channelContract, "challenge", new Object[]{channelId}, "wasm"});
        return extractReturnValue(result);
    }

    private String extractReturnValue(Object result) {
        if (result instanceof com.fasterxml.jackson.databind.JsonNode) {
            com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) result;
            if (node.has("returnValue")) {
                return node.get("returnValue").asText();
            }
        }
        return result != null ? result.toString() : null;
    }

    private void requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private void requirePositive(BigInteger value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 余额证明。
     */
    public static class BalanceProof {
        private final String channelId;
        private final BigInteger balance;
        private final long nonce;
        private final String signature;

        public BalanceProof(String channelId, BigInteger balance, long nonce, String signature) {
            this.channelId = channelId;
            this.balance = balance;
            this.nonce = nonce;
            this.signature = signature;
        }

        public String getChannelId() { return channelId; }
        public BigInteger getBalance() { return balance; }
        public long getNonce() { return nonce; }
        public String getSignature() { return signature; }
    }

    /**
     * 通道信息。
     */
    public static class ChannelInfo {
        private final String channelId;
        private final String sender;
        private final String recipient;
        private final BigInteger deposit;
        private final String status;
        private final long openBlock;

        public ChannelInfo(String channelId, String sender, String recipient,
                           BigInteger deposit, String status, long openBlock) {
            this.channelId = channelId;
            this.sender = sender;
            this.recipient = recipient;
            this.deposit = deposit;
            this.status = status;
            this.openBlock = openBlock;
        }

        public String getChannelId() { return channelId; }
        public String getSender() { return sender; }
        public String getRecipient() { return recipient; }
        public BigInteger getDeposit() { return deposit; }
        public String getStatus() { return status; }
        public long getOpenBlock() { return openBlock; }
    }
}
