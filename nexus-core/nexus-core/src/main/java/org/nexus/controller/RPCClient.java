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

package org.nexus.controller;

import jakarta.annotation.PreDestroy;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.core.account.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RPCClient {
    @Autowired
    private ConsensusConfig consensusConfig;

    @Autowired
    private JSONEncodeDecoder codec;

    private static final int HTTP_TIMEOUT = 5000;

    private CloseableHttpClient httpclient;

    /**
     * REST 形态的合批窗口（毫秒），与 gRPC 形态共用同一组配置：
     * {@code <= 0} 表示关闭合批，逐笔立即广播。
     *
     * <p>2026-10-27：{@code p2p.mode=rest} 此前未纳入合批 —— 每受理一笔交易就
     * 对每个 peer 发一次 HTTP POST。接收端 {@code /consensus/transactions}
     * （{@code ConsensusController.handleTransactions}）本来就按列表遍历校验，
     * 一个 {@code Packet} 携带多笔是协议支持的，因此这里补齐发送侧的合并。</p>
     */
    @Value("${p2p.broadcast-batch-window-ms:20}")
    private int batchWindowMs = 20;

    /** 合批上限：达到该数量立即冲刷，不等窗口到期。 */
    @Value("${p2p.broadcast-batch-max-transactions:1000}")
    private int batchMaxTransactions = 1000;

    private final Queue<Transaction> pendingTransactions = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> windowFlush;

    private volatile ScheduledExecutorService flushExecutor;

    private static final Logger logger = LoggerFactory.getLogger(RPCClient.class);

    public RPCClient() {
        httpclient = HttpClients.custom()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .setConnectionManagerShared(true)
                .build();
    }

    private static byte[] getBody(final HttpResponse response) {
        int status = response.getStatusLine().getStatusCode();
        if (status < 200 || status >= 300) {
            return null;
        }
        try {
            HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toByteArray(entity) : null;
        } catch (Exception e) {
            logger.error("Failed to read HTTP response body", e);
            return null;
        }
    }

    private static Object noop(Object o) {
        return null;
    }

    public void post(String url, byte[] body, Callback<byte[], Object> cb) {
        Optional<CloseableHttpResponse> resp = Optional.empty();
        try {
            URI urio = new URI(url);
            HttpPost httppost = new HttpPost(urio);
            httppost.setConfig(RequestConfig.custom().setConnectTimeout(HTTP_TIMEOUT).build());
            httppost.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
            // Create a custom response handler
            resp = Optional.of(httpclient.execute(httppost));
            Optional<byte[]> responseBody = resp.map(RPCClient::getBody);
            if (responseBody.isPresent()) {
                logger.info("response received = " + responseBody.map(String::new));
            } else {
                logger.error("post " + url + " fail");
            }
            responseBody.map(cb::call);
        } catch (Exception e) {
            logger.error("post " + url + " fail", e);
        } finally {
            resp.map(x -> {
                try {
                    x.close();
                } catch (Exception e) {
                    logger.warn("Failed to close HTTP response", e);
                    return false;
                }
                return true;
            });
        }
    }

    public void broadcast(String path, byte[] body) {
        for (String hostPort : consensusConfig.getPeers()) {
            post("http://" + hostPort + path, body, RPCClient::noop);
        }
    }

    public void get(String uri, Map<String, String> query, Callback<byte[], Object> cb) {
        Optional<CloseableHttpResponse> resp = Optional.empty();
        try {
            URI urio = new URI(uri);
            URIBuilder builder = new URIBuilder()
                    .setScheme(urio.getScheme())
                    .setHost(urio.getHost())
                    .setPort(urio.getPort())
                    .setPath(urio.getPath());
            for (String k : query.keySet()) {
                builder.setParameter(k, query.get(k));
            }
            urio = builder.build();
            uri = urio.toString();
            HttpGet httpget = new HttpGet(urio);
            httpget.setConfig(RequestConfig.custom().setConnectTimeout(HTTP_TIMEOUT).build());
            // Create a custom response handler

            resp = Optional.of(httpclient.execute(httpget));
            Optional<byte[]> responseBody = resp.map(RPCClient::getBody);
            if (responseBody.isPresent()) {
                logger.info("response received = " + responseBody.map(String::new));
            } else {
                logger.error("get " + uri + " fail");
            }
            responseBody.map(cb::call);
        } catch (Exception e) {
            logger.error("get " + uri + " fail", e);
        } finally {
            resp.map(x -> {
                try {
                    x.close();
                } catch (Exception e) {
                    logger.warn("Failed to close HTTP response", e);
                    return false;
                }
                return true;
            });
        }
    }


    @Async
    public void relayPacket(Packet packet) {
        packet.dec();
        broadcast("/consensus/transactions", codec.encodePacket(packet));
    }

    /**
     * REST 形态广播本地受理的交易。
     *
     * <p>与 gRPC 形态（{@code TransactionHandler}）一致：窗口内的多笔合并成一个
     * {@code Packet} 再发给每个 peer，把「每笔 × 每个 peer」的 HTTP 往返降为
     * 「每批 × 每个 peer」。{@code p2p.broadcast-batch-window-ms <= 0} 时保持
     * 逐笔立即广播的旧语义。</p>
     */
    @Async
    public void broadcastTransactions(List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return;
        }
        if (batchWindowMs <= 0) {
            broadcast("/consensus/transactions",
                    codec.encodePacket(new Packet(codec.encodeTransactions(txs), 8)));
            return;
        }
        pendingTransactions.addAll(txs);
        scheduleFlush(pendingTransactions.size() >= batchMaxTransactions ? 0L : batchWindowMs);
    }

    private void scheduleFlush(long delayMs) {
        if (delayMs <= 0) {
            ScheduledFuture<?> pending = windowFlush;
            if (pending != null && pending.cancel(false)) {
                flushScheduled.set(false);
            }
            if (flushScheduled.compareAndSet(false, true)) {
                try {
                    executor().execute(this::flushPending);
                } catch (RejectedExecutionException e) {
                    flushScheduled.set(false);
                    flushPending();
                }
            }
            return;
        }
        if (flushScheduled.compareAndSet(false, true)) {
            try {
                windowFlush = executor().schedule(this::flushPending, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                flushScheduled.set(false);
                flushPending();
            }
        }
    }

    /** 取出待发交易：按交易哈希折叠重复项后合成一个 Packet 广播。 */
    private void flushPending() {
        flushScheduled.set(false);
        windowFlush = null;

        List<Transaction> batch = new ArrayList<>();
        Transaction tx;
        while ((tx = pendingTransactions.poll()) != null) {
            batch.add(tx);
        }
        if (batch.isEmpty()) {
            return;
        }
        // 同一笔交易在窗口内可能被重复提交（重试/重复投递）；逐笔广播时每个包各自去重，
        // 合并成一批后必须显式折叠，否则会把同一笔重复带上线。
        Map<String, Transaction> unique = new LinkedHashMap<>();
        for (Transaction t : batch) {
            unique.putIfAbsent(Hex.encodeHexString(t.getHash()), t);
        }
        broadcast("/consensus/transactions",
                codec.encodePacket(new Packet(codec.encodeTransactions(new ArrayList<>(unique.values())), 8)));
    }

    private ScheduledExecutorService executor() {
        ScheduledExecutorService ex = flushExecutor;
        if (ex == null) {
            synchronized (this) {
                ex = flushExecutor;
                if (ex == null) {
                    ex = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "p2p-rest-broadcast-flush");
                        t.setDaemon(true);
                        return t;
                    });
                    flushExecutor = ex;
                }
            }
        }
        return ex;
    }

    /** 停机时把窗口内未到期的交易同步发出，避免静默丢弃。 */
    @PreDestroy
    public void shutdown() {
        flushPending();
        ScheduledExecutorService ex = flushExecutor;
        if (ex != null) {
            ex.shutdown();
        }
    }
}
