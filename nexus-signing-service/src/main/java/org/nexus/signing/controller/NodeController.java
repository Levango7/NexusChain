package org.nexus.signing.controller;

import com.google.gson.JsonObject;
import org.nexus.signing.util.HttpRequestUtil;
import org.nexus.sdk.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 链节点 RPC 封装控制器。
 *
 * <p>从 {@code org.nexus.wallet.wallet.controller.NodeController}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.controller}。</p>
 *
 * <p>封装链节点的 RPC 接口：getNonce / sendTransaction / getTransactionConfirmed。
 * 签名服务广播签名结果时直接调用本类，不再绕行 gateway 的 OnChainExecutionClient。</p>
 */
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