package org.nexus.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NexusChain JSON-RPC client with HTTP transport.
 */
public class RpcClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final String rpcUrl;
    private final int timeoutMs;
    private final String apiKey;
    private final CloseableHttpClient http;

    public RpcClient(String rpcUrl, int timeoutMs, String apiKey) {
        this.rpcUrl = rpcUrl;
        this.timeoutMs = timeoutMs;
        this.apiKey = apiKey;
        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        this.http = HttpClients.custom().setDefaultRequestConfig(rc).build();
    }

    /** Convenience: timeout 30s, no API key. */
    public RpcClient(String rpcUrl) {
        this(rpcUrl, 30000, null);
    }

    public Object call(String method, Object... params) {
        return call(method, params, Object.class);
    }

    @SuppressWarnings("unchecked")
    public <T> T call(String method, Object[] params, Class<T> resultType) {
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", NEXT_ID.getAndIncrement());
            req.put("method", method);
            ArrayNode p = req.putArray("params");
            if (params != null) for (Object o : params) p.addPOJO(o);

            HttpPost post = new HttpPost(rpcUrl);
            post.setHeader("Content-Type", "application/json");
            if (apiKey != null) post.setHeader("Authorization", "Bearer " + apiKey);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(req), "UTF-8"));

            try (CloseableHttpResponse resp = http.execute(post)) {
                String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
                JsonNode node = MAPPER.readTree(body);
                if (node.has("error")) {
                    throw new RpcException(node.get("error").get("message").asText());
                }
                JsonNode result = node.get("result");
                if (resultType == Object.class) return (T) result;
                return MAPPER.treeToValue(result, resultType);
            }
        } catch (IOException e) {
            throw new RpcException("RPC call failed: " + method, e);
        }
    }

    public Object[] batchCall(RpcRequest[] requests) {
        try {
            ArrayNode batch = MAPPER.createArrayNode();
            for (RpcRequest r : requests) {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("jsonrpc", "2.0");
                req.put("id", r.getId());
                req.put("method", r.getMethod());
                ArrayNode p = req.putArray("params");
                if (r.getParams() != null) for (Object o : r.getParams()) p.addPOJO(o);
                batch.add(req);
            }
            HttpPost post = new HttpPost(rpcUrl);
            post.setHeader("Content-Type", "application/json");
            if (apiKey != null) post.setHeader("Authorization", "Bearer " + apiKey);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(batch), "UTF-8"));

            try (CloseableHttpResponse resp = http.execute(post)) {
                String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
                return MAPPER.readValue(body, Object[].class);
            }
        } catch (IOException e) {
            throw new RpcException("Batch RPC call failed", e);
        }
    }

    /**
     * 查询当前区块高度。
     *
     * 兼容实现：nexus-core 未提供 nexus_blockNumber，改为调用 nexus_getLatestBlocks
     * 取最新区块列表中的第一个区块高度。对齐 Go/TypeScript SDK。
     */
    public long getBlockNumber() {
        Object result = call("nexus_getLatestBlocks", 1);
        if (result instanceof JsonNode) {
            JsonNode node = (JsonNode) result;
            if (node.isArray()) {
                if (node.isEmpty()) return 0L;
                JsonNode first = node.get(0);
                if (first.isObject()) {
                    if (first.has("height")) return parseLong(first.get("height"));
                    if (first.has("number")) return parseLong(first.get("number"));
                }
                return parseLong(first);
            }
            return parseLong(node);
        }
        return result instanceof Number ? ((Number) result).longValue() : Long.parseLong(result.toString());
    }

    /**
     * 根据 hash 获取区块信息。
     *
     * 注意：nexus-core 当前未提供 nexus_getBlockByHash，保留接口以兼容旧 SDK 用户。
     * 实际应通过 nexus_getBlockByHeight 配合索引服务使用。
     *
     * @param blockHash 区块哈希
     * @deprecated nexus-core 不支持按 hash 查询区块，请使用 {@link #getBlockByNumber(long)} 代替
     */
    @Deprecated
    public Map<String, Object> getBlockByHash(String blockHash) {
        throw new UnsupportedOperationException(
                "nexus_getBlockByHash not supported by nexus-core; use getBlockByNumber instead");
    }

    /**
     * 根据区块号获取区块信息。
     *
     * 对齐 nexus-core：nexus_getBlockByNumber → nexus_getBlockByHeight。
     *
     * @param blockNumber 区块号
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBlockByNumber(long blockNumber) {
        return call("nexus_getBlockByHeight", new Object[]{String.valueOf(blockNumber)}, Map.class);
    }

    /**
     * 获取网络链 ID。
     *
     * 兼容实现：nexus-core 未提供 nexus_chainId，改为调用 nexus_getNodeStatus
     * 从节点状态中获取 chainId 字段。对齐 Go/TypeScript SDK。
     */
    public long getChainId() {
        Object result = call("nexus_getNodeStatus");
        if (result instanceof JsonNode) {
            JsonNode node = (JsonNode) result;
            if (node.isObject()) {
                if (node.has("chainId")) return parseLong(node.get("chainId"));
                if (node.has("chain_id")) return parseLong(node.get("chain_id"));
            }
            return parseLong(node);
        }
        return result instanceof Number ? ((Number) result).longValue() : Long.parseLong(result.toString());
    }

    /**
     * 解析 JsonNode 为 long，兼容十进制、0x 前缀十六进制字符串、数字三种形式。
     */
    private static long parseLong(JsonNode node) {
        if (node == null || node.isNull()) return 0L;
        if (node.isNumber()) return node.asLong();
        String s = node.asText();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseLong(s.substring(2), 16);
        }
        return Long.parseLong(s);
    }

    public void close() throws IOException { http.close(); }

    // --- inner types ---

    public static class RpcRequest {
        private final String method;
        private final Object[] params;
        private final long id;
        public RpcRequest(String method, Object[] params, long id) {
            this.method = method; this.params = params; this.id = id;
        }
        public String getMethod() { return method; }
        public Object[] getParams() { return params; }
        public long getId() { return id; }
    }

    public static class RpcException extends RuntimeException {
        public RpcException(String msg) { super(msg); }
        public RpcException(String msg, Throwable cause) { super(msg, cause); }
    }
}
