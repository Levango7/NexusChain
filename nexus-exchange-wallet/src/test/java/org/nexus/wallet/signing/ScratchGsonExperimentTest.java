package org.nexus.wallet.signing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.wallet.util.JsonUtil;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Scratch experiment — prints Gson serialization of the TxUtils ObjectNode. */
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
