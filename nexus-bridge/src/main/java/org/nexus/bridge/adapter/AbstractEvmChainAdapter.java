package org.nexus.bridge.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * EVM 兼容链适配器抽象基类。
 *
 * <p>封装 Web3j 与任意 EVM 兼容链（Ethereum / BSC / Polygon 等）
 * 的通用 JSON-RPC 交互逻辑。子类只需提供链 ID 与 RPC 端点即可。</p>
 *
 * <h2>实现的 RPC 方法</h2>
 * <ul>
 *   <li>{@code eth_chainId} — 通过 {@link Web3j#ethChainId()} 获取</li>
 *   <li>{@code eth_blockNumber} — 通过 {@link Web3j#ethBlockNumber()} 获取最新区块高度</li>
 *   <li>{@code eth_sendRawTransaction} — 通过 {@link Web3j#ethSendRawTransaction(String)} 发送原始交易</li>
 *   <li>{@code eth_getTransactionReceipt} — 通过 {@link Web3j#ethGetTransactionReceipt(String)} 查询回执</li>
 *   <li>{@code eth_call} — 通过 {@link Web3j#ethCall(Transaction, DefaultBlockParameterName)} 只读调用合约</li>
 * </ul>
 *
 * <h2>错误处理</h2>
 * <p>所有 IO 异常统一捕获并记录日志，返回安全默认值（如 {@code -1L} 区块高度、
 * {@code null} 回执），避免异常传播导致上层服务不可用。
 * {@link #sendTransaction(byte[])} 在 RPC 失败时抛出
 * {@link RuntimeException}，因为交易发送失败属于不可恢复的严重错误。</p>
 *
 * @since 1.2
 */
public abstract class AbstractEvmChainAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractEvmChainAdapter.class);

    /** Web3j 客户端实例，由子类指定的 RPC 端点构建。 */
    protected final Web3j web3j;

    /** 链 ID（十六进制字符串形式，如 "0x1"）。 */
    private final String chainId;

    /**
     * 构造 EVM 链适配器。
     *
     * @param chainId     链 ID（十六进制字符串，如 "0x1"）
     * @param rpcEndpoint RPC 端点 URL（如 "https://mainnet.infura.io/v3/..."）
     */
    protected AbstractEvmChainAdapter(String chainId, String rpcEndpoint) {
        this.chainId = chainId;
        this.web3j = Web3j.build(new HttpService(rpcEndpoint));
        log.info("Initialized {} with chainId={}, rpcEndpoint={}",
                getClass().getSimpleName(), chainId, rpcEndpoint);
    }

    @Override
    public String getChainId() {
        return chainId;
    }

    @Override
    public long getBlockHeight() {
        try {
            return web3j.ethBlockNumber()
                    .send()
                    .getBlockNumber()
                    .longValueExact();
        } catch (IOException e) {
            log.error("Failed to fetch block height on chain {}: {}", chainId, e.getMessage());
            return -1L;
        } catch (Exception e) {
            log.error("Unexpected error fetching block height on chain {}: {}", chainId, e.getMessage());
            return -1L;
        }
    }

    @Override
    public String sendTransaction(byte[] tx) {
        if (tx == null || tx.length == 0) {
            throw new IllegalArgumentException("Transaction bytes must not be null or empty");
        }
        // 将原始字节编码为 0x 前缀的十六进制字符串，符合 eth_sendRawTransaction 规范
        String hexPayload = "0x" + HexFormat.of().formatHex(tx);
        try {
            EthSendTransaction response = web3j.ethSendRawTransaction(hexPayload).send();
            if (response.hasError()) {
                log.error("eth_sendRawTransaction failed on chain {}: code={}, message={}",
                        chainId, response.getError().getCode(), response.getError().getMessage());
                throw new RuntimeException("Transaction rejected by chain " + chainId
                        + ": " + response.getError().getMessage());
            }
            String txHash = response.getTransactionHash();
            log.debug("Submitted tx on chain {}: hash={}", chainId, txHash);
            return txHash;
        } catch (IOException e) {
            log.error("IO error sending transaction on chain {}: {}", chainId, e.getMessage());
            throw new RuntimeException("IO error sending transaction on chain " + chainId, e);
        }
    }

    @Override
    public Object getTransactionReceipt(String hash) {
        if (hash == null || hash.isEmpty()) {
            log.warn("Empty transaction hash supplied to getTransactionReceipt on chain {}", chainId);
            return null;
        }
        try {
            EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(hash).send();
            if (response.hasError()) {
                log.error("eth_getTransactionReceipt failed on chain {}: code={}, message={}",
                        chainId, response.getError().getCode(), response.getError().getMessage());
                return null;
            }
            return response.getTransactionReceipt().orElse(null);
        } catch (IOException e) {
            log.error("IO error fetching receipt {} on chain {}: {}", hash, chainId, e.getMessage());
            return null;
        }
    }

    @Override
    public String callContract(String address, String data) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Contract address must not be null or empty");
        }
        // 规范化 calldata：允许传入不带 0x 前缀的 hex
        String normalizedData = (data == null || data.isEmpty()) ? "0x" : data;
        if (!normalizedData.startsWith("0x")) {
            normalizedData = "0x" + normalizedData;
        }
        try {
            // from 设为空地址，表示纯只读 view 调用
            Transaction call = Transaction.createEthCallTransaction(
                    "0x0000000000000000000000000000000000000000", address, normalizedData);
            EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) {
                log.error("eth_call failed on chain {} for contract {}: code={}, message={}",
                        chainId, address, response.getError().getCode(), response.getError().getMessage());
                return null;
            }
            return response.getValue();
        } catch (IOException e) {
            log.error("IO error calling contract {} on chain {}: {}", address, chainId, e.getMessage());
            return null;
        }
    }

    /**
     * 关闭 Web3j 客户端，释放底层 HTTP 连接资源。
     *
     * <p>由 Spring 容器在销毁 Bean 时调用，或在适配器生命周期结束时手动调用。</p>
     */
    public void shutdown() {
        if (web3j != null) {
            web3j.shutdown();
            log.info("Shutdown Web3j client for chain {}", chainId);
        }
    }

    /**
     * 将字符串以 UTF-8 编码为字节，再转十六进制（不带 0x 前缀）。
     *
     * @param value 原始字符串
     * @return 十六进制字符串
     */
    protected static String utf8ToHex(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
}