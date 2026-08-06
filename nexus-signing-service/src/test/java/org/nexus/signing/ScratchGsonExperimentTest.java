package org.nexus.signing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.sdk.util.JsonUtil;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Gson 序列化实验测试。
 *
 * <p>从 {@code org.nexus.wallet.signing.ScratchGsonExperimentTest}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing}。</p>
 *
 * <p>验证 TxUtils 产出的 ObjectNode 经 Gson 序列化后的字段结构，
 * 确保 TxController 返回的 data 字段（交易哈希）不丢失。</p>
 */
public class ScratchGsonExperimentTest {

    @Test
    public void printGsonOutput() {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String prikey = WalletUtils.obtainPrikey(keystoreJson, password);
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);
        String toHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);

        ObjectNode data = TxUtils.ClientToTransferAccount(pubkey, toHash, new BigDecimal("100"), prikey, 0L);
        System.out.println("OBJECTNODE_JSON=" + data);
        System.out.println("GSON_TOJSON=" + JsonUtil.GSON.toJson(data));
        Map<String, Object> dataMap = JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(data), HashMap.class);
        System.out.println("DATAMAP_KEYS=" + dataMap.keySet());
        System.out.println("DATAMAP_DATA=" + dataMap.get("data"));
    }
}