/*
 * NexusChain Explorer ↔ Core JSON-RPC 2.0 桥接端点（v2 桥接）
 *
 * 背景：nexus-explorer/backend/src/rpc.ts 期望对 core 调用 12 个 nexus_* JSON-RPC
 * 方法。此前 core 主代码未暴露任何 JSON-RPC 服务（jsonrpc4j 仅在 test 依赖），
 * 导致 Explorer 后端调用 core 全部 method-not-found / 连接拒绝。
 *
 * 本控制器手写一个最小 JSON-RPC 2.0 dispatcher，按方法名路由到 core 真实数据 API，
 * 并将 core 的 Block / Transaction 原生对象翻译为 Explorer 期望的 RpcBlock /
 * RpcTransaction 字段形状（注意：core 的 encodeBlock/encodeTransaction 原生 JSON
 * 中 byte[] 走 Jackson 默认 base64、字段名亦不符，故不直出原生 JSON，而是显式构造）。
 *
 * 接线状态（均需在具备 Gradle/JDK 的真机上构建验证 —— 沙箱无工具链）：
 *   ✅ nexus_getBalance           → AccountDB.getBalance(KeystoreAction.addressToPubkeyHash(addr))
 *   ✅ nexus_getTransactionCount  → AccountDB.getNonce(...)
 *   ✅ nexus_getBlockByHeight     → NexusChainBlockChain.getCanonicalBlock(h) → RpcBlock（字段翻译）
 *   ✅ nexus_getLatestBlocks      → currentHeader() 向下循环 getCanonicalBlock → RpcBlock[]
 *   ✅ nexus_getTransactionByHash → NexusChainBlockChain.getTransaction(h)    → RpcTransaction（字段翻译）
 *   ✅ nexus_getLatestTransactions→ 遍历最近区块体收集 tx → RpcTransaction[]
 *   ✅ nexus_getTransactionsByAddress → to 维度用 getTransactionsByTo（全量历史）+ from 维度遍历近期区块
 *   🟡 nexus_getNodeStatus        → latestHeight + latestHash 真实；chainId 由 ${nexus.chain-id:0}
 *                                   外部化配置；peers / syncing 桩（core 运行态未暴露）
 *   ✅ nexus_getCrossChainTransactions → 遍历最近区块体筛选 BRIDGE_* 交易，解析 payload 还原跨链字段（RpcCrossChainTx）
 *   ✅ nexus_getContractList / nexus_getContract
 *      → 委托 ContractRegistry（org.nexus.core.contract）：内存 ConcurrentHashMap + LevelDB 双层注册表，
 *        nexus_registerContract 手动注册（部署工具调用），列表/详情查询返回真实合约元数据
 *   ✅ nexus_registerContract        → ContractRegistry.register()，写入即落盘 LevelDB（@since contract-subsystem）
 *
 * 端点：POST /rpc  （Explorer 后端的 NEXUS_RPC_URL 应指向 http://<core>:19585/rpc）
 */
package org.nexus.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.Block;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.core.contract.ContractRegistry;
import org.nexus.core.contract.ContractStatus;
import org.nexus.core.contract.RegisteredContract;
import org.nexus.util.Address;
import org.nexus.keystore.wallet.KeystoreAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class JsonRpcController {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // JSON-RPC 2.0 错误码
    private static final int CODE_INVALID_REQUEST = -32600;
    private static final int CODE_METHOD_NOT_FOUND = -32601;
    private static final int CODE_INVALID_PARAMS = -32602;
    private static final int CODE_INTERNAL = -32603;
    private static final int CODE_NOT_IMPLEMENTED = -32000;
    private static final int CODE_NOT_FOUND = -32001;
    // 合约子系统专用错误码（与 design.md 2.2.2 对齐）
    private static final int CODE_CONTRACT_INVALID = -32002; // 参数缺失/地址格式非法
    private static final int CODE_ALREADY_REGISTERED = -32003; // 合约已注册

    @Autowired
    NexusChainBlockChain bc;

    @Autowired
    AccountDB accountDB;

    // 合约注册表（@Autowired(required=false) 兼容 registry-enabled=false 或 Spring 排除场景）
    @Autowired(required = false)
    ContractRegistry contractRegistry;

    // 链 ID 外部化配置（core application.yml 可设 nexus.chain-id，默认 0 待真机填真实值）
    @Value("${nexus.chain-id:0}")
    int chainId;

    // 合约列表返回上限（与 ContractRegistry.maxListSize 对齐，RPC 层二次夹逼）
    @Value("${nexus.contract.max-list-size:100}")
    int contractMaxListSize;

    @PostMapping(value = "/rpc", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String handleRpc(@RequestBody String raw) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        long id = 0;
        try {
            ObjectNode req = (ObjectNode) MAPPER.readTree(raw);
            if (!"2.0".equals(req.path("jsonrpc").asText())) {
                return error(CODE_INVALID_REQUEST, "jsonrpc field must be \"2.0\"", id).toString();
            }
            id = req.path("id").asLong(0);
            response.put("id", id);

            String method = req.path("method").asText();
            ArrayNode params = req.has("params") && req.get("params").isArray()
                    ? (ArrayNode) req.get("params") : MAPPER.createArrayNode();

            Object result = dispatch(method, params);
            if (result instanceof ObjectNode) {
                response.set("error", (ObjectNode) result); // 已是 error 信封
            } else {
                response.set("result", MAPPER.valueToTree(result));
            }
        } catch (Exception e) {
            logger.warn("JSON-RPC dispatch failed: {}", e.getMessage());
            response.put("id", id);
            response.set("error", error(CODE_INVALID_REQUEST, "malformed JSON-RPC request: " + e.getMessage(), id));
        }
        return response.toString();
    }

    private Object dispatch(String method, ArrayNode params) {
        try {
            switch (method) {
                case "nexus_getBalance":
                    return doGetBalance(params);
                case "nexus_getTransactionCount":
                    return doGetTransactionCount(params);
                case "nexus_getBlockByHeight":
                    return doGetBlockByHeight(params);
                case "nexus_getLatestBlocks":
                    return doGetLatestBlocks(params);
                case "nexus_getTransactionByHash":
                    return doGetTransactionByHash(params);
                case "nexus_getLatestTransactions":
                    return doGetLatestTransactions(params);
                case "nexus_getTransactionsByAddress":
                    return doGetTransactionsByAddress(params);
                case "nexus_getNodeStatus":
                    return doGetNodeStatus(params);
                // —— 跨链：基于链上 BRIDGE_* 交易推导；合约：ContractRegistry 内存+LevelDB 双层 ——
                case "nexus_getCrossChainTransactions":
                    return doGetCrossChainTransactions(params);
                case "nexus_getContractList":
                    return doGetContractList(params);
                case "nexus_getContract":
                    return doGetContract(params);
                case "nexus_registerContract":
                    return doRegisterContract(params);
                default:
                    return error(CODE_METHOD_NOT_FOUND, "method not found: " + method, 0);
            }
        } catch (IllegalArgumentException e) {
            return error(CODE_INVALID_PARAMS, e.getMessage(), 0);
        } catch (Exception e) {
            logger.warn("RPC method {} failed: {}", method, e.getMessage());
            return error(CODE_INTERNAL, method + " internal error: " + e.getMessage(), 0);
        }
    }

    // ✅ 地址余额：address -> pubkeyHash -> AccountDB.getBalance
    private Object doGetBalance(ArrayNode params) {
        String address = paramString(params, 0, "address");
        byte[] pubkeyHash = KeystoreAction.addressToPubkeyHash(address);
        long balance = accountDB.getBalance(pubkeyHash);
        Map<String, String> m = new HashMap<>();
        m.put("balance", String.valueOf(balance));
        return m;
    }

    // ✅ 地址交易计数（nonce）：address -> pubkeyHash -> AccountDB.getNonce
    private Object doGetTransactionCount(ArrayNode params) {
        String address = paramString(params, 0, "address");
        byte[] pubkeyHash = KeystoreAction.addressToPubkeyHash(address);
        long nonce = accountDB.getNonce(pubkeyHash);
        Map<String, Long> m = new HashMap<>();
        m.put("count", nonce);
        return m;
    }

    // ✅ 按高度取块 → Explorer RpcBlock（字段已翻译，非原生 encodeBlock JSON）
    private Object doGetBlockByHeight(ArrayNode params) {
        long height = paramLong(params, 0, "height");
        Block b = bc.getCanonicalBlock(height);
        if (b == null) {
            return error(CODE_NOT_FOUND, "block not found at height = " + height, 0);
        }
        return toRpcBlock(b);
    }

    // ✅ 最新区块列表 → Explorer RpcBlock[]（从 currentHeader 向下循环）
    private Object doGetLatestBlocks(ArrayNode params) {
        int limit = (int) paramLongWithDefault(params, 0, 20L, "limit");
        if (limit <= 0 || limit > 100) limit = 20;
        Block head = bc.currentHeader();
        long top = head == null ? 0 : head.nHeight;
        List<ObjectNode> blocks = new ArrayList<>();
        for (long h = top; h >= 0 && blocks.size() < limit; h--) {
            Block b = bc.getCanonicalBlock(h);
            if (b == null) continue;
            blocks.add(toRpcBlock(b));
        }
        return blocks;
    }

    // ✅ 按哈希取交易 → Explorer RpcTransaction（字段已翻译）
    private Object doGetTransactionByHash(ArrayNode params) {
        String hash = paramString(params, 0, "hash");
        byte[] hashBytes;
        try {
            hashBytes = Hex.decodeHex(hash);
        } catch (DecoderException e) {
            return error(CODE_INVALID_PARAMS, "invalid hash hex: " + hash, 0);
        }
        Transaction tx = bc.getTransaction(hashBytes);
        if (tx == null) {
            return error(CODE_NOT_FOUND, "transaction not found: " + hash, 0);
        }
        return toRpcTransaction(tx);
    }

    // ✅ 最新交易列表 → Explorer RpcTransaction[]（遍历最近区块体）
    private Object doGetLatestTransactions(ArrayNode params) {
        int limit = (int) paramLongWithDefault(params, 0, 20L, "limit");
        if (limit <= 0 || limit > 100) limit = 20;
        return collectLatestTransactions(limit);
    }

    // ✅ 按地址取相关交易 → Explorer RpcTransaction[]
    //    to 维度用 core 索引 API getTransactionsByTo（全量历史）；from 维度遍历近期区块体
    //    （core 无 address→pubkey 反查 API，故以 pubkeyHash 比较）
    private Object doGetTransactionsByAddress(ArrayNode params) {
        String address = paramString(params, 0, "address");
        int limit = (int) paramLongWithDefault(params, 1, 20L, "limit");
        if (limit <= 0 || limit > 100) limit = 20;
        byte[] target = Address.getPublicKeyHash(address);
        if (target == null) {
            return error(CODE_INVALID_PARAMS, "invalid address: " + address, 0);
        }
        List<ObjectNode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // to 维度：core 已暴露的索引 API，直接覆盖全部历史
        List<Transaction> toTxs = bc.getTransactionsByTo(target, 0, limit);
        if (toTxs != null) {
            for (Transaction tx : toTxs) {
                String h = tx.getHashHexString();
                if (seen.add(h)) {
                    result.add(toRpcTransaction(tx));
                }
            }
        }
        // from 维度：core 无 address→pubkey 反查，遍历近期区块体以 pubkeyHash 比对
        Block head = bc.currentHeader();
        long top = head == null ? 0 : head.nHeight;
        long scanFloor = Math.max(0, top - 200);
        for (long h = top; h >= scanFloor && result.size() < limit; h--) {
            Block b = bc.getCanonicalBlock(h);
            if (b == null || b.body == null) continue;
            for (Transaction tx : b.body) {
                if (java.util.Arrays.equals(Address.publicKeyToHash(tx.from), target)) {
                    String hx = tx.getHashHexString();
                    if (seen.add(hx)) {
                        result.add(toRpcTransaction(tx));
                        if (result.size() >= limit) break;
                    }
                }
            }
        }
        return result;
    }

    // 🟡 节点状态：latestHeight + latestHash 真实；chainId 外部化；peers/syncing 桩
    private Object doGetNodeStatus(ArrayNode params) {
        Map<String, Object> m = new HashMap<>();
        Block head = bc.currentHeader();
        m.put("chainId", chainId);
        m.put("latestHeight", head == null ? 0 : head.nHeight);
        m.put("latestHash", head == null ? "" : head.getHashHexString());
        m.put("syncing", false);
        m.put("peers", 0);
        m.put("version", "v2-rpc-bridge");
        return m;
    }

    // ---- Block → RpcBlock 字段翻译 ----
    private ObjectNode toRpcBlock(Block b) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("height", b.nHeight);
        n.put("hash", b.getHashHexString());
        n.put("parentHash", b.hashPrevBlock == null ? "" : Hex.encodeHexString(b.hashPrevBlock));
        n.put("timestamp", b.nTime);
        int txCount = (b.body == null) ? 0 : b.body.size();
        n.put("txCount", txCount);
        // PoW 出块者：取区块体首笔交易（coinbase）的 to 地址近似
        n.put("proposer", (b.body != null && !b.body.isEmpty())
                ? Address.publicKeyHashToAddress(b.body.get(0).to) : "");
        ArrayNode txs = MAPPER.createArrayNode();
        if (b.body != null) {
            for (Transaction tx : b.body) {
                txs.add(tx.getHashHexString());
            }
        }
        n.set("transactions", txs);
        return n;
    }

    // ---- Transaction → RpcTransaction 字段翻译 ----
    private ObjectNode toRpcTransaction(Transaction tx) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("txHash", tx.getHashHexString());
        n.put("blockHeight", tx.height);
        n.put("from", Address.publicKeyToAddress(tx.from));
        n.put("to", Address.publicKeyHashToAddress(tx.to));
        n.put("amount", String.valueOf(tx.amount));
        // 已上链交易视为 success；pending 不适用于 RPC 查询（查的是已确认交易）
        n.put("status", "success");
        // tx 本身无时间戳，反查其所在区块的 nTime
        long ts = 0;
        if (tx.height > 0) {
            Block b = bc.getCanonicalBlock(tx.height);
            if (b != null) ts = b.nTime;
        }
        n.put("timestamp", ts);
        n.put("data", (tx.payload != null) ? Hex.encodeHexString(tx.payload) : null);
        return n;
    }

    // ✅ 跨链交易列表 → Explorer RpcCrossChainTx[]
    //    core 的 BridgeService.getStatus 为骨架 mock（不持久化跨链域记录），真实跨链数据在链上：
    //    BRIDGE_LOCK 的 payload 即 BridgeTransaction.toJson()（含 bridgeTxId/sourceChain/targetChain/
    //    recipient/amount/state/timestamp）；BRIDGE_MINT/BURN 的 payload 含精简 JSON。
    //    故遍历最近区块体筛选 BRIDGE_* 交易并解析 payload，与 getLatestTransactions 同口径。
    private Object doGetCrossChainTransactions(ArrayNode params) {
        int limit = (int) paramLongWithDefault(params, 0, 20L, "limit");
        if (limit <= 0 || limit > 100) limit = 20;
        String statusFilter = (params.size() > 1 && !params.get(1).isNull())
                ? params.get(1).asText().trim().toLowerCase() : null;

        List<ObjectNode> result = new ArrayList<>();
        Block head = bc.currentHeader();
        long top = head == null ? 0 : head.nHeight;
        long scanFloor = Math.max(0, top - 200);
        for (long h = top; h >= scanFloor && result.size() < limit; h--) {
            Block b = bc.getCanonicalBlock(h);
            if (b == null || b.body == null) continue;
            for (Transaction tx : b.body) {
                if (!tx.isBridgeTransaction()) continue;
                ObjectNode cc = toRpcCrossChainTx(tx);
                if (cc == null) continue;
                if (statusFilter != null && !statusFilter.isEmpty()) {
                    String st = cc.path("status").asText("");
                    if (!statusFilter.equals(st.toLowerCase())) continue;
                }
                result.add(cc);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    // ---- 跨链交易 → RpcCrossChainTx 字段翻译（基于链上 BRIDGE_* 交易 + payload）----
    private ObjectNode toRpcCrossChainTx(Transaction tx) {
        ObjectNode n = MAPPER.createObjectNode();
        long ts = (tx.height > 0) ? blockTimeSafe(tx.height) : 0L;
        String fromAddr = Address.publicKeyToAddress(tx.from);
        String toAddr = Address.publicKeyHashToAddress(tx.to);
        String amountStr = String.valueOf(tx.amount);
        String sourceChain = "NEX";
        String targetChain = "?";
        String status = "pending";

        if (tx.payload != null && tx.payload.length > 0) {
            try {
                ObjectNode p = (ObjectNode) MAPPER.readTree(
                        new String(tx.payload, StandardCharsets.UTF_8));
                if (p.has("bridgeTxId")) n.put("txId", p.get("bridgeTxId").asText());
                if (p.has("amount")) amountStr = p.get("amount").asText();
                if (p.has("recipient")) toAddr = p.get("recipient").asText();
                int type = tx.type;
                if (type == Transaction.Type.BRIDGE_LOCK.ordinal()) {
                    sourceChain = "NEX";
                    if (p.has("targetChain")) targetChain = p.get("targetChain").asText();
                    status = bridgeStateToRpcStatus(p.path("state").asText("LOCKED"));
                } else if (type == Transaction.Type.BRIDGE_MINT.ordinal()) {
                    if (p.has("sourceChain")) sourceChain = p.get("sourceChain").asText();
                    targetChain = "NEX";
                    status = "confirmed";
                } else if (type == Transaction.Type.BRIDGE_BURN.ordinal()) {
                    sourceChain = "NEX";
                    if (p.has("targetChain")) targetChain = p.get("targetChain").asText();
                    status = "confirmed";
                }
                if (p.has("timestamp")) ts = p.get("timestamp").asLong(ts);
            } catch (Exception e) {
                // payload 解析失败则回退到交易本身字段（使用上面默认值）
                logger.debug("crosschain payload parse failed for {}: {}",
                        tx.getHashHexString(), e.getMessage());
            }
        }
        if (!n.has("txId")) n.put("txId", tx.getHashHexString());
        n.put("sourceChain", sourceChain);
        n.put("targetChain", targetChain);
        n.put("amount", amountStr);
        n.put("status", status);
        n.put("timestamp", ts);
        n.put("from", fromAddr);
        n.put("to", toAddr);
        return n;
    }

    // ✅ 合约列表：委托 ContractRegistry.list(0, limit)，按 createdAt 倒序，列表项无 wasmCode/abi
    private Object doGetContractList(ArrayNode params) {
        if (contractRegistry == null) {
            return new ArrayList<ObjectNode>();
        }
        int limit = (int) paramLongWithDefault(params, 0, 50L, "limit");
        if (limit <= 0 || limit > contractMaxListSize) {
            limit = Math.min(50, contractMaxListSize);
            if (limit <= 0) limit = 50;
        }
        List<RegisteredContract> contracts = contractRegistry.list(0, limit);
        List<ObjectNode> result = new ArrayList<>();
        for (RegisteredContract rc : contracts) {
            result.add(toRpcContractListItem(rc));
        }
        return result;
    }

    // ✅ 合约详情：委托 ContractRegistry.getByAddress(addr)，命中翻译为 RpcContract 详情形状（含 abi/wasmCode）
    private Object doGetContract(ArrayNode params) {
        if (params.size() <= 0 || params.get(0).isNull()) {
            return error(CODE_CONTRACT_INVALID, "missing param: address", 0);
        }
        String address = params.get(0).asText();
        if (!isValidAddress(address)) {
            return error(CODE_CONTRACT_INVALID, "invalid address: " + address, 0);
        }
        if (contractRegistry == null) {
            return error(CODE_NOT_FOUND, "contract not found (registry unavailable): " + address, 0);
        }
        RegisteredContract rc = contractRegistry.getByAddress(address);
        if (rc == null) {
            return error(CODE_NOT_FOUND, "contract not found: " + address, 0);
        }
        return toRpcContractDetail(rc);
    }

    // ✅ 合约手动注册：解析参数构造 RegisteredContract，调 ContractRegistry.register()
    //    params: [address, name, abi, codeHash, wasmCode, creationBlock, creator]
    private Object doRegisterContract(ArrayNode params) {
        if (contractRegistry == null) {
            return error(CODE_INTERNAL, "contract registry unavailable", 0);
        }
        if (params.size() < 7) {
            return error(CODE_CONTRACT_INVALID,
                    "missing params: expected [address, name, abi, codeHash, wasmCode, creationBlock, creator]", 0);
        }
        String address = params.get(0).isNull() ? null : params.get(0).asText();
        String name = params.get(1).isNull() ? null : params.get(1).asText();
        JsonNode abiNode = params.get(2).isNull() ? null : params.get(2);
        String codeHash = params.get(3).isNull() ? null : params.get(3).asText();
        String wasmCode = params.get(4).isNull() ? null : params.get(4).asText();
        long creationBlock = params.get(5).isNull() ? -1L : params.get(5).asLong();
        String creator = params.get(6).isNull() ? null : params.get(6).asText();

        if (address == null || address.isEmpty()) {
            return error(CODE_CONTRACT_INVALID, "missing or empty address", 0);
        }
        if (!isValidAddress(address)) {
            return error(CODE_CONTRACT_INVALID, "invalid address: " + address, 0);
        }
        if (name == null || name.isEmpty()) {
            return error(CODE_CONTRACT_INVALID, "missing or empty name", 0);
        }
        if (codeHash == null || codeHash.isEmpty()) {
            return error(CODE_CONTRACT_INVALID, "missing or empty codeHash", 0);
        }
        if (contractRegistry.exists(address)) {
            return error(CODE_ALREADY_REGISTERED, "contract already registered: " + address, 0);
        }

        // abi 参数支持 JSON 数组/对象（toString 得 JSON 字符串）或纯字符串（asText）
        String abiStr = (abiNode == null) ? "" : (abiNode.isTextual() ? abiNode.asText() : abiNode.toString());
        long createdAt = System.currentTimeMillis() / 1000L;
        RegisteredContract rc = new RegisteredContract(
                address, name, abiStr, codeHash, wasmCode, creator,
                creationBlock, createdAt, chainId, ContractStatus.ACTIVE);
        boolean ok = contractRegistry.register(rc);
        if (!ok) {
            return error(CODE_ALREADY_REGISTERED, "contract already registered: " + address, 0);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("address", address);
        m.put("registered", true);
        return m;
    }

    // ---- RegisteredContract → RpcContract 列表项（精简形状，无 wasmCode/abi）----
    private ObjectNode toRpcContractListItem(RegisteredContract rc) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("address", rc.getAddress());
        n.put("name", rc.getName());
        n.put("creator", rc.getCreator() == null ? "" : rc.getCreator());
        n.put("codeHash", rc.getCodeHash() == null ? "" : rc.getCodeHash());
        n.put("createdAt", rc.getCreatedAt());
        n.put("creationBlock", rc.getCreationBlock());
        n.put("chainId", rc.getChainId());
        n.put("status", rc.getStatus() == null ? "ACTIVE" : rc.getStatus().name());
        return n;
    }

    // ---- RegisteredContract → RpcContract 详情（含 wasmCode/abi，字段对齐 Explorer RpcContract）----
    private ObjectNode toRpcContractDetail(RegisteredContract rc) {
        ObjectNode n = toRpcContractListItem(rc);
        n.put("wasmCode", rc.getWasmCode() == null ? "" : rc.getWasmCode());
        if (rc.getAbi() != null && !rc.getAbi().isEmpty()) {
            try {
                n.set("abi", MAPPER.readTree(rc.getAbi()));
            } catch (Exception e) {
                // abi 非合法 JSON 则回退为字符串输出
                n.put("abi", rc.getAbi());
            }
        } else {
            n.putNull("abi");
        }
        return n;
    }

    // ---- 合约地址格式校验：0x 前缀 + 偶数个 hex 字符 ----
    private boolean isValidAddress(String address) {
        if (address == null || address.length() < 4) return false;
        if (!address.startsWith("0x") && !address.startsWith("0X")) return false;
        try {
            Hex.decodeHex(address.substring(2).toCharArray());
            return true;
        } catch (DecoderException e) {
            return false;
        }
    }

    // ---- 区块时间戳安全反查（区块缺失时返回 0）----
    private long blockTimeSafe(long height) {
        Block b = bc.getCanonicalBlock(height);
        return b == null ? 0L : b.nTime;
    }

    // ---- 桥交易域状态 → Explorer RpcCrossChainTx.status ----
    private String bridgeStateToRpcStatus(String state) {
        if (state == null) return "pending";
        switch (state) {
            case "MINTED":
            case "BURNED":
            case "UNLOCKED":
                return "confirmed";
            case "FAILED":
            case "EXPIRED":
                return "failed";
            default: // PENDING / LOCKED / VALIDATING
                return "pending";
        }
    }

    // ---- 遍历最近区块体收集最新 tx ----
    private List<ObjectNode> collectLatestTransactions(int limit) {
        List<ObjectNode> result = new ArrayList<>();
        Block head = bc.currentHeader();
        long top = head == null ? 0 : head.nHeight;
        long scanFloor = Math.max(0, top - 200);
        for (long h = top; h >= scanFloor && result.size() < limit; h--) {
            Block b = bc.getCanonicalBlock(h);
            if (b == null || b.body == null) continue;
            for (Transaction tx : b.body) {
                result.add(toRpcTransaction(tx));
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    // ---- 参数解析辅助 ----
    private String paramString(ArrayNode params, int idx, String name) {
        if (params.size() <= idx || params.get(idx).isNull()) {
            throw new IllegalArgumentException("missing param: " + name);
        }
        return params.get(idx).asText();
    }

    private long paramLong(ArrayNode params, int idx, String name) {
        if (params.size() <= idx || params.get(idx).isNull()) {
            throw new IllegalArgumentException("missing param: " + name);
        }
        return params.get(idx).asLong();
    }

    private long paramLongWithDefault(ArrayNode params, int idx, long def, String name) {
        if (params.size() <= idx || params.get(idx).isNull()) {
            return def;
        }
        try {
            return params.get(idx).asLong();
        } catch (Exception e) {
            return def;
        }
    }

    private ObjectNode error(int code, String message, long id) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        return e;
    }
}
