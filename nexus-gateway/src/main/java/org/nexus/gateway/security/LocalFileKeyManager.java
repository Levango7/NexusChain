package org.nexus.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"dev", "prod"})
public class LocalFileKeyManager implements KeyManager {

    private static final Logger log = LoggerFactory.getLogger(LocalFileKeyManager.class);
    private final ConcurrentHashMap<Long, String[]> cache = new ConcurrentHashMap<>();
    private final Path keyStorePath;

    public LocalFileKeyManager(@Value("${nexus.keystore.path:}") String path) {
        if (path != null && !path.isEmpty()) {
            this.keyStorePath = Paths.get(path);
        } else {
            this.keyStorePath = Paths.get(System.getProperty("java.io.tmpdir"), "nexus-keystore.properties");
        }
        loadFromFile();
    }

    @Override
    public String getPublicKey(Long merchantId) {
        String[] pair = cache.get(merchantId);
        return pair != null ? pair[0] : null;
    }

    @Override
    public String getPrivateKey(Long merchantId) {
        String[] pair = cache.get(merchantId);
        return pair != null ? pair[1] : null;
    }

    @Override
    public void storeKeypair(Long merchantId, String publicKey, String privateKey) {
        cache.put(merchantId, new String[]{publicKey, privateKey});
        persistToFile();
        log.info("Keypair stored for merchant: {}", merchantId);
    }

    @Override
    public boolean hasKeypair(Long merchantId) {
        return cache.containsKey(merchantId);
    }

    private void loadFromFile() {
        if (!Files.exists(keyStorePath)) { return; }
        try (InputStream is = Files.newInputStream(keyStorePath)) {
            Properties props = new Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                String[] parts = props.getProperty(key).split(",", 2);
                if (parts.length == 2) { cache.put(Long.parseLong(key), parts); }
            }
            log.info("Loaded {} keypairs from {}", cache.size(), keyStorePath);
        } catch (IOException | RuntimeException e) {
            // 静默吞异常修复：加载失败会导致 cache 为空，后续 getPublicKey/getPrivateKey 返回 null，
            // 商户签名失败但原因难以诊断。提高日志级别并抛出 IllegalStateException，让构造失败暴露问题。
            log.error("Failed to load keystore from {}: {}", keyStorePath, e.getMessage(), e);
            throw new IllegalStateException("Failed to load keystore from " + keyStorePath, e);
        }
    }

    private synchronized void persistToFile() {
        try {
            Properties props = new Properties();
            cache.forEach((id, pair) -> props.setProperty(id.toString(), pair[0] + "," + pair[1]));
            try (OutputStream os = Files.newOutputStream(keyStorePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(os, "NexusChain Dev Keystore");
            }
        } catch (IOException | RuntimeException e) {
            // 静默吞异常修复：持久化失败时 cache 已更新但文件未写入，重启后数据丢失。
            // 抛出 IllegalStateException 让调用方 storeKeypair 感知失败，避免静默数据丢失。
            log.error("Failed to persist keystore to {}: {}", keyStorePath, e.getMessage(), e);
            throw new IllegalStateException("Failed to persist keystore to " + keyStorePath, e);
        }
    }
}