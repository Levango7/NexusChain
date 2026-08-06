package org.nexus.wallet.wallet.pool;

import org.nexus.wallet.util.JsonUtil;
import org.nexus.wallet.Leveldb.Leveldb;
import org.nexus.wallet.Utils.BeanToMapUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NoncePool {

    private ConcurrentHashMap<String, TreeMap<Long, NonceState>> noncepool;
    private Leveldb leveldb;

    @Autowired
    public NoncePool(Leveldb leveldb) {
        this.leveldb=leveldb;
        try{
            this.noncepool = new ConcurrentHashMap<>();
            //序列化获取
            String dbdata=leveldb.readFromSnapshot();
            if(dbdata!=null && !dbdata.equals("")){
                Map<String, Map<Integer, NonceState>> maps= JsonUtil.GSON.fromJson(dbdata,HashMap.class);
                for(Map.Entry<String, Map<Integer, NonceState>> entry:maps.entrySet()){
                    String key=entry.getKey();
                    Map<Integer, NonceState> map=entry.getValue();
                    TreeMap<Long, NonceState> treemap=new TreeMap<>();
                    for(Map.Entry<Integer, NonceState> entry1:map.entrySet()){
                        int treekey=entry1.getKey();
                        long key2=treekey;
                        NonceState nonceState= (NonceState) BeanToMapUtil.convertMap(NonceState.class, (Map) entry1.getValue());
                        treemap.put(key2,nonceState);
                    }
                    noncepool.put(key,treemap);
                }
            }
        }catch (Exception e){
            this.noncepool = new ConcurrentHashMap<>();
        }
    }

    public ConcurrentHashMap<String, TreeMap<Long, NonceState>> getNoncepool() {
        return noncepool;
    }

    public void add(String address,NonceState nonceState) throws IOException {
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            long nownonce=nonceState.getNonce();
            if(!tmaps.containsKey(nownonce)){
                tmaps.put(nonceState.getNonce(),nonceState);
            }
        }else{
            TreeMap<Long, NonceState> treemap=new TreeMap<>();
            treemap.put(nonceState.getNonce(),nonceState);
            noncepool.put(address,treemap);
        }
        String json = JsonUtil.GSON_PRETTY.toJson(noncepool);
        leveldb.addPoolDb(json);
    }

    public void remove(String address,long nonce) throws IOException {
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            if(tmaps.containsKey(nonce)){
                tmaps.remove(nonce);
            }
            if(tmaps.size()==0){
                noncepool.remove(address);
            }
        }
        String json = JsonUtil.GSON_PRETTY.toJson(noncepool);
        leveldb.addPoolDb(json);
    }

    public long getMinNonce(String address){
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            return tmaps.firstKey();
        }
        return 0;
    }

    public long getMaxNonce(String address){
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            return tmaps.lastKey();
        }
        return 0;
    }

    public TreeMap<Long, NonceState> getTreemap(String address) {
        if (noncepool.containsKey(address)) {
            return noncepool.get(address);
        }
        return new TreeMap<>();
    }

//    public static void main(String args[]){
//        TreeMap<Integer,String> tree = new TreeMap<Integer,String>();
//        tree.put(1, "唐僧");
//        tree.put(2, "李白");
//        tree.put(5, "白居易");
//        tree.put(3, "孙悟空");
//        tree.put(2, "李黑");
//        tree.put(3, "李彤");
//        System.out.println(tree);
//        System.out.println("get(1):"+tree.get(1)+" get(2)"+tree.get(2));
//        System.out.println(tree.firstKey()+"--->"+tree.lastKey());
//    }
}
