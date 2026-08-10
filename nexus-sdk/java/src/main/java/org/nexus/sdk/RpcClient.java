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

    public long getBlockNumber() {
        Object result = call("nexus_blockNumber");
        return result instanceof Number ? ((Number) result).longValue() : Long.parseLong(result.toString());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getBlockByHash(String blockHash) {
        return call("nexus_getBlockByHash", new Object[]{blockHash}, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getBlockByNumber(long blockNumber) {
        return call("nexus_getBlockByNumber", new Object[]{String.valueOf(blockNumber)}, Map.class);
    }

    public long getChainId() {
        Object result = call("nexus_chainId");
        return result instanceof Number ? ((Number) result).longValue() : Long.parseLong(result.toString());
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
