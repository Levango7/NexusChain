package org.nexus.wallet.pool;

import com.google.gson.JsonObject;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.wallet.controller.NodeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

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

//    public static void main(String agrs[]) throws ParseException {
//        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
//        Date date1 = simpleDateFormat.parse("2019-08-01 14:22:30");
//        Date date2 = simpleDateFormat.parse("2019-08-01 14:26:29");
//        long mul=(date2.getTime() - date1.getTime()) / (60 * 1000);
//        System.out.println(mul);
//    }
}
