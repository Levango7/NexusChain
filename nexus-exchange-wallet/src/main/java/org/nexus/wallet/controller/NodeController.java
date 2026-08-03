package org.nexus.wallet.controller;

import com.google.gson.JsonObject;
import org.nexus.wallet.Utils.HttpRequestUtil;
import org.nexus.wallet.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NodeController {

    @Value("${nodeNet}")
    private String ip;


    /**
     * 获取nonce
     * @param pubkeyhash
     * @return
     */
    public JsonObject getNonce(String pubkeyhash){
        String url = "http://"+ip+"/sendNonce";
        String param = "pubkeyhash="+pubkeyhash;
        String result = HttpRequestUtil.sendPost(url,param);
        if (result == null || result.isEmpty()) {
            return null;
        }
        return JsonUtil.GSON.fromJson(result, JsonObject.class);
    }

    /**
     * 通过事务哈希获取区块确认状态
     * @param txHash
     * @return
     */
    public JsonObject getTransactionConfirmed(String txHash){
        String url = "http://"+ip+"/transactionConfirmed";
        String param = "txHash="+txHash;
        String result = HttpRequestUtil.sendGet(url,param);
        if (result == null || result.isEmpty()) {
            return null;
        }
        return JsonUtil.GSON.fromJson(result, JsonObject.class);
    }

    /**
     * 广播事务
     * @param traninfo
     * @return
     */
    public JsonObject sendTransaction(String traninfo){
        String url = "http://"+ip+"/sendTransaction";
        String param = "traninfo="+traninfo;
        String result = HttpRequestUtil.sendPost(url,param);
        if (result == null || result.isEmpty()) {
            return null;
        }
        return JsonUtil.GSON.fromJson(result, JsonObject.class);
    }

}
