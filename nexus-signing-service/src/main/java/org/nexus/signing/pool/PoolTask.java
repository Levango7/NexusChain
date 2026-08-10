package org.nexus.signing.pool;

import com.google.gson.JsonObject;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.signing.controller.NodeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Nonce 池定时清理任务。
 *
 * <p>从 {@code org.nexus.wallet.wallet.pool.PoolTask}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.pool}。</p>
 *
 * <p>定时（10s）检查 Nonce 池中已上链的 Nonce，清理已确认的条目。</p>
 */
@Component
public class PoolTask {

    public NoncePool noncePool;
    public NodeController nodeController;

    @Autowired
    public PoolTask(NoncePool noncePool,NodeController nodeController){
        this.noncePool=noncePool;
        this.nodeController= nodeController;
    }

    @Scheduled(fixedDelay = 10 * 1000)
    public void task() throws IOException {
        Map<String, TreeMap<Long, NonceState>> noncepool=noncePool.getNoncepool();
        for(Map.Entry<String, TreeMap<Long, NonceState>> entry:noncepool.entrySet()){
            TreeMap<Long, NonceState> treeMap=entry.getValue();
            long firstkey=treeMap.firstKey();
            //rpc获取nonce
            JsonObject getnonoce=nodeController.getNonce(WalletUtils.addressToPubkeyHash(entry.getKey()));
            int Codes= getnonoce != null && getnonoce.has("code") ? getnonoce.get("code").getAsInt() : 0;
            if(Codes==2000){
                long nownonce= getnonoce != null && getnonoce.has("data") ? getnonoce.get("data").getAsLong() : 0;
                if(nownonce>=firstkey){
                    noncePool.remove(entry.getKey(),firstkey);
                    continue;
                }
                NonceState nonceState=treeMap.get(firstkey);
                if(nonceState!=null){
                    //判断txhash是否存在
                    JsonObject result=nodeController.getTransactionConfirmed(nonceState.getTranHash());
                    int Code= result != null && result.has("code") ? result.get("code").getAsInt() : 0;
                    if(Code==2000){
                        noncePool.remove(entry.getKey(),firstkey);
                    }
                }else{
                    noncePool.remove(entry.getKey(),firstkey);
                }
            }
        }
    }
}