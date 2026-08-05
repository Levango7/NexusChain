package org.nexus.sdk.stablecoin;

import org.nexus.sdk.RpcClient;

import java.math.BigInteger;

/**
 * 稳定币客户端。
 *
 * <p>提供 NexusChain 网络上稳定币的发行、销毁、转账、抵押率和价格查询能力。
 * 稳定币通过超额抵押 NEX 或其他资产铸造。</p>
 *
 * <p>写操作（mint / burn / transfer）经 RPC 调用稳定币合约；读操作
 * （抵押率 / 价格 / 总供应量）经 RPC 查询。价格返回锚定价（peg），
 * 来源标识由服务端 {@code price-source} 配置决定（默认 PEG）。</p>
 */
public class StableCoinClient {

    /** 稳定币合约默认地址。 */
    private static final String DEFAULT_STABLECOIN_CONTRACT = "0x0000000000000000000000000000000000005ab1";

    /** 价格精度指数（价格以 10^18 缩放）。 */
    private static final BigInteger PRICE_SCALE = BigInteger.TEN.pow(18);

    private final RpcClient rpcClient;
    private final String stableCoinContract;

    public StableCoinClient(RpcClient rpcClient) {
        this(rpcClient, DEFAULT_STABLECOIN_CONTRACT);
    }

    public StableCoinClient(RpcClient rpcClient, String stableCoinContract) {
        if (rpcClient == null) {
            throw new IllegalArgumentException("rpcClient is required");
        }
        this.rpcClient = rpcClient;
        this.stableCoinContract = stableCoinContract != null ? stableCoinContract : DEFAULT_STABLECOIN_CONTRACT;
    }

    /**
     * 铸造（发行）稳定币。
     *
     * @param minter    铸造者地址
     * @param amount    铸造数量（最小单位）
     * @param collateral 抵押资产数量（NEX，最小单位 wei）
     * @return 铸造交易哈希
     */
    public String mint(String minter, BigInteger amount, BigInteger collateral) {
        requireNonEmpty(minter, "minter");
        requirePositive(amount, "amount");
        requirePositive(collateral, "collateral");

        Object result = rpcClient.call("nexus_callContract",
                new Object[]{stableCoinContract, "mint", new Object[]{minter, amount, collateral}, "wasm"});
        return extractReturnValue(result);
    }

    /**
     * 销毁稳定币并释放抵押物。
     *
     * @param burner  销毁者地址
     * @param amount  销毁数量（最小单位）
     * @return 销毁交易哈希
     */
    public String burn(String burner, BigInteger amount) {
        requireNonEmpty(burner, "burner");
        requirePositive(amount, "amount");

        Object result = rpcClient.call("nexus_callContract",
                new Object[]{stableCoinContract, "burn", new Object[]{burner, amount}, "wasm"});
        return extractReturnValue(result);
    }

    /**
     * 稳定币转账。
     *
     * @param from   发送方地址
     * @param to     接收方地址
     * @param amount 转账数量（最小单位）
     * @return 转账交易哈希
     */
    public String transfer(String from, String to, BigInteger amount) {
        requireNonEmpty(from, "from");
        requireNonEmpty(to, "to");
        requirePositive(amount, "amount");

        Object result = rpcClient.call("nexus_callContract",
                new Object[]{stableCoinContract, "transfer", new Object[]{from, to, amount}, "wasm"});
        return extractReturnValue(result);
    }

    /**
     * 查询地址的抵押率。
     *
     * @param address 用户地址
     * @return 当前抵押率（百分比，如 150.00 表示 150%，返回 150）
     */
    public BigInteger getCollateralRatio(String address) {
        requireNonEmpty(address, "address");
        Object result = rpcClient.call("nexus_queryContract",
                new Object[]{stableCoinContract, "getCollateralRatio", new Object[]{address}, "wasm"});
        String value = extractReturnValue(result);
        return value != null ? new BigInteger(value) : BigInteger.ZERO;
    }

    /**
     * 查询稳定币当前价格。
     *
     * @return 稳定币价格（以美元计，乘以 10^18 的整数）。锚定价 1.00 返回 10^18。
     */
    public BigInteger getPrice() {
        Object result = rpcClient.call("nexus_queryContract",
                new Object[]{stableCoinContract, "getPrice", new Object[]{}, "wasm"});
        String value = extractReturnValue(result);
        if (value == null) {
            return PRICE_SCALE; // 默认锚定价 1.00
        }
        try {
            return new BigInteger(value);
        } catch (NumberFormatException e) {
            return PRICE_SCALE;
        }
    }

    /**
     * 查询稳定币总供应量。
     *
     * @return 总供应量（最小单位）
     */
    public BigInteger getTotalSupply() {
        Object result = rpcClient.call("nexus_queryContract",
                new Object[]{stableCoinContract, "totalSupply", new Object[]{}, "wasm"});
        String value = extractReturnValue(result);
        return value != null ? new BigInteger(value) : BigInteger.ZERO;
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
}
