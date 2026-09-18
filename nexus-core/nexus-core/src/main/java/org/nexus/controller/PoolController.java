package org.nexus.controller;

// TD-07/TD-08（tech-debt-audit）：由 net.sf.json-lib 迁移至 Jackson 树模型
// （ObjectNode/ArrayNode），序列化输出语义保持不变。
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.keystore.util.JsonUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.nexus.pool.AdoptTransPool;
import org.nexus.pool.PendingNonce;
import org.nexus.pool.PeningTransPool;
import org.nexus.pool.TransPool;

import java.text.SimpleDateFormat;
import java.util.*;

@RestController
public class PoolController {

    private static final Logger logger = LoggerFactory.getLogger(PoolController.class);

    /**
     * 池管理端点令牌校验的期望摘要（sha3-256 的 hex 小写）。
     *
     * <p><b>S-2 修复（2026-09-17 交付前审计）</b>：{@code /deletePendpool} 与
     * {@code /updatePtNonce} 原本把该期望值硬编码在源码里
     * （{@code "a772c260ae19e8972f1da3af77492fdb6b40f34a9b34b4a9021ecfd900f21e53"}）——
     * 随源码公开、无法轮换，且无配置化审计痕迹。现改为配置注入
     * （{@code nexus.pool.admin-token-sha3} / 环境变量 {@code NEXUS_POOL_ADMIN_TOKEN_SHA3}）：
     * <b>未配置时一律拒绝（fail-closed）</b>，配置后可按需轮换。</p>
     *
     * <p>迁移：把旧期望摘要通过该配置注入即可保持既有调用方可用；
     * 但旧值已进入 git 历史，建议轮换为新令牌后再对外提供服务。</p>
     */
    @Value("${nexus.pool.admin-token-sha3:}")
    private String adminTokenSha3;

    /**
     * 校验调用方给出的 {@code tokenhash} 派生摘要是否为合法的池管理令牌。
     *
     * <p>判定与历史实现保持一致：{@code sha3_256(hexDecode(tokenhash))} 的 hex 小写形式
     * 等于配置的期望摘要。比较使用常量时间实现（避免时序侧信道），
     * 配置缺失时直接拒绝（fail-closed）。</p>
     *
     * @param tokenHashSha3Hex 调用方 tokenhash 经 sha3-256 后的 hex（大小写不敏感）
     * @return 合法返回 {@code true}
     */
    private boolean isPoolAdminToken(String tokenHashSha3Hex) {
        if (adminTokenSha3 == null || adminTokenSha3.isEmpty()) {
            logger.warn("pool admin endpoint rejected: nexus.pool.admin-token-sha3 is not configured (fail-closed)");
            return false;
        }
        String expected = adminTokenSha3.trim().toLowerCase(Locale.ROOT);
        String actual = tokenHashSha3Hex == null ? "" : tokenHashSha3Hex.toLowerCase(Locale.ROOT);
        return org.nexus.util.Arrays.constantTimeAreEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Autowired
    TransactionPool transactionPool;

    @Autowired
    AdoptTransPool adoptTransPool;

    @Autowired
    PeningTransPool peningTransPool;

    @RequestMapping(value="/getPoolAddress",method = RequestMethod.GET)
    public Object getPoolAddress(@RequestParam("address") String address){
        ArrayNode jsonArray = JsonUtils.MAPPER.createArrayNode();
        int check=KeystoreAction.verifyAddress(address);
        if(check==0){
            byte[] pubkeyhash=KeystoreAction.addressToPubkeyHash(address);
            List<TransPool> adoptpool=adoptTransPool.getAllFrom(Hex.encodeHexString(pubkeyhash));
            for(TransPool transPool:adoptpool){
                Transaction transaction=transPool.getTransaction();
                ObjectNode json = JsonUtils.createObjectNode();
                json.put("pool","AdoptTransPool");
                json.put("traninfo",Hex.encodeHexString(transaction.toRPCBytes()));
                json.put("tranhash",Hex.encodeHexString(transaction.getHash()));
                json.put("type",transaction.type);
                json.put("nonce",transaction.nonce);
                json.put("fromhash", Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from))));
                json.put("amount",transaction.amount);
                json.put("fee",transaction.getFee());
                json.put("to",Hex.encodeHexString(transaction.to));
                if(transaction.payload==null){
                    json.putNull("payload");
                }else{
                    json.put("payload",Hex.encodeHexString(transaction.payload));
                }
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date date = new Date(transPool.getDatetime());
                json.put("datatime",sdf.format(date));
                jsonArray.add(json);
            }
            List<TransPool> pendingpool=peningTransPool.getAllFrom(Hex.encodeHexString(pubkeyhash));
            for(TransPool transPool:pendingpool){
                Transaction transaction=transPool.getTransaction();
                ObjectNode json = JsonUtils.createObjectNode();
                json.put("pool","PendingTransPool");
                json.put("traninfo",Hex.encodeHexString(transaction.toRPCBytes()));
                json.put("tranhash",Hex.encodeHexString(transaction.getHash()));
                json.put("type",transaction.type);
                json.put("nonce",transaction.nonce);
                json.put("fromhash", Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from))));
                json.put("amount",transaction.amount);
                json.put("fee",transaction.getFee());
                json.put("to",Hex.encodeHexString(transaction.to));
                if(transaction.payload==null){
                    json.putNull("payload");
                }else{
                    json.put("payload",Hex.encodeHexString(transaction.payload));
                }
                json.put("state",transPool.getState());
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date date = new Date(transPool.getDatetime());
                json.put("datatime",sdf.format(date));
                json.put("height",transPool.getHeight());
                jsonArray.add(json);
            }
            List<ObjectNode> jsonValues = new ArrayList<ObjectNode>();
            for (int i = 0; i < jsonArray.size(); i++) {
                jsonValues.add((ObjectNode) jsonArray.get(i));
            }
            Collections.sort(jsonValues, new Comparator<ObjectNode>() {
                @Override
                public int compare(ObjectNode o1, ObjectNode o2) {
                    long nonce1=o1.get("nonce").asLong();
                    long nonce2=o2.get("nonce").asLong();
                    return (int)(nonce1-nonce2);
                }
            });
            // 保持原 json-lib 逻辑：先整体字符串化再解析回 JSON 数组（行为等价的深拷贝）
            ArrayNode jsonArray1=(ArrayNode) JsonUtils.readTree(jsonValues.toString());
            return APIResult.newFailResult(2000,"SUCCESS",jsonArray1);
        }else{
            return APIResult.newFailResult(5000,"Address check error");
        }
    }

    @RequestMapping(value="/getPoolTranhash",method = RequestMethod.GET)
    public Object getPoolTranhash(@RequestParam("tranhash") String tranhash){
        try {
            if(tranhash!=null && !tranhash.equals("")){
                byte[] txhash=Hex.decodeHex(tranhash.toCharArray());
                TransPool adoptpool=adoptTransPool.getPoolTranHash(txhash);
                if(adoptpool!=null){
                    Transaction transaction=adoptpool.getTransaction();
                    ObjectNode json = JsonUtils.createObjectNode();
                    json.put("pool","AdoptTransPool");
                    json.put("traninfo",Hex.encodeHexString(transaction.toRPCBytes()));
                    json.put("tranhash",Hex.encodeHexString(transaction.getHash()));
                    json.put("type",transaction.type);
                    json.put("nonce",transaction.nonce);
                    json.put("fromhash", Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from))));
                    json.put("amount",transaction.amount);
                    json.put("fee",transaction.getFee());
                    json.put("to",Hex.encodeHexString(transaction.to));
                    if(transaction.payload==null){
                        json.putNull("payload");
                    }else{
                        json.put("payload",Hex.encodeHexString(transaction.payload));
                    }
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = new Date(adoptpool.getDatetime());
                    json.put("datatime",sdf.format(date));
                    return APIResult.newFailResult(2000,"SUCCESS",json);
                }
                TransPool pendingpool=peningTransPool.getPoolTranHash(txhash);
                if(pendingpool!=null){
                    Transaction transaction=pendingpool.getTransaction();
                    ObjectNode json = JsonUtils.createObjectNode();
                    json.put("pool","PendingTransPool");
                    json.put("traninfo",Hex.encodeHexString(transaction.toRPCBytes()));
                    json.put("tranhash",Hex.encodeHexString(transaction.getHash()));
                    json.put("type",transaction.type);
                    json.put("nonce",transaction.nonce);
                    json.put("fromhash", Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from))));
                    json.put("amount",transaction.amount);
                    json.put("fee",transaction.getFee());
                    json.put("to",Hex.encodeHexString(transaction.to));
                    if(transaction.payload==null){
                        json.putNull("payload");
                    }else{
                        json.put("payload",Hex.encodeHexString(transaction.payload));
                    }
                    json.put("state",pendingpool.getState());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = new Date(pendingpool.getDatetime());
                    json.put("datatime",sdf.format(date));
                    json.put("height",pendingpool.getHeight());
                    return APIResult.newFailResult(2000,"SUCCESS",json);
                }
                return APIResult.newFailResult(2000,"Not in memory pool");
            }else{
                return APIResult.newFailResult(5000,"The parameter address cannot be empty or null");
            }
        } catch (DecoderException e) {
            return APIResult.newFailResult(5000,"Exception error");
        }
    }

    @RequestMapping(value="/getPoolCount",method = RequestMethod.GET)
    public Object getPoolCount(){
        int adoptcount=adoptTransPool.getAllFull().size();
        List<TransPool> pengdingcount=peningTransPool.getAllnostate();
        int pengcount=pengdingcount.size();
        ObjectNode json = JsonUtils.createObjectNode();
        json.put("adoptcount",adoptcount);
        json.put("pengcount",pengcount);
        return APIResult.newFailResult(2000,"SUCCESS",json);
    }

    @RequestMapping(value="/getPoolInfo",method = RequestMethod.GET)
    public Object getPoolInfo(){
        ObjectNode json = JsonUtils.createObjectNode();
        json.put("QueuePool", PoolJson(adoptTransPool.getAllFull(),0));
        json.put("PendPool",PoolJson(peningTransPool.getAll(),1));
        return APIResult.newFailResult(2000,"SUCCESS",json);
    }

    @RequestMapping(value="/getPtNonce",method = RequestMethod.GET)
    public Object getPtNonce(@RequestParam("address") String address){
        try{
            byte[] pubkeyhash=KeystoreAction.addressToPubkeyHash(address);
            PendingNonce pendingNonce=peningTransPool.findptnonce(Hex.encodeHexString(pubkeyhash));
            return APIResult.newFailResult(2000,"SUCCESS",pendingNonce);
        }catch (RuntimeException e){
            return APIResult.newFailResult(5000,"Address error");
        }
    }

    @RequestMapping(value="/deletePendpool",method = RequestMethod.POST)
    public Object deletePendpool(@RequestParam("tokenhash") String tokenhash,
                                 @RequestParam("txhash") String txhash){
        try {
            byte[] hash=Hex.decodeHex(tokenhash.toCharArray());
            byte[] shahash=SHA3Utility.sha3256(hash);
            String token=Hex.encodeHexString(shahash);
            if(!isPoolAdminToken(token)){
                return APIResult.newFailResult(5000,"Token check but");
            }
            TransPool transPool=peningTransPool.getPoolTranHash(Hex.decodeHex(txhash.toCharArray()));
            if(transPool!=null){
                Transaction transaction=transPool.getTransaction();
                String fromhash=Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from)));
                peningTransPool.removeOne(fromhash,transaction.nonce);
                return APIResult.newFailResult(2000,"SUCCESS");
            }else{
                return  APIResult.newFailResult(5000,"Transaction hash query not found");
            }
        } catch (DecoderException e) {
            return APIResult.newFailResult(5000,"Token conversions are problematic 16");
        }
    }

    @RequestMapping(value="/updatePtNonce",method = RequestMethod.POST)
    public Object updatePtNonce(@RequestParam("tokenhash") String tokenhash, @RequestParam("address") String address,
                                @RequestParam("nonce") long nonce, @RequestParam("state") int state){
        try{
            byte[] hash=Hex.decodeHex(tokenhash.toCharArray());
            byte[] shahash=SHA3Utility.sha3256(hash);
            String token=Hex.encodeHexString(shahash);
            if(!isPoolAdminToken(token)){
                return APIResult.newFailResult(5000,"Token check but");
            }
            byte[] pubkeyhash=KeystoreAction.addressToPubkeyHash(address);
            PendingNonce pendingNonce=new PendingNonce(nonce,state);
            peningTransPool.updatePtNone(Hex.encodeHexString(pubkeyhash),pendingNonce);
            return APIResult.newFailResult(2000,"SUCCESS");
        }catch (RuntimeException | org.apache.commons.codec.DecoderException e){
            return APIResult.newFailResult(5000,"Address error");
        }
    }

    public static ArrayNode PoolJson(List<TransPool> pool,int state){
        ArrayNode jsonArray = JsonUtils.MAPPER.createArrayNode();
        String type="AdoptTransPool";
        if(state==1){
            type="PendingTransPool";
        }
        for(TransPool transPool:pool){
            Transaction transaction=transPool.getTransaction();
            ObjectNode json = JsonUtils.createObjectNode();
            json.put("pool",type);
            json.put("traninfo",Hex.encodeHexString(transaction.toRPCBytes()));
            json.put("tranhash",Hex.encodeHexString(transaction.getHash()));
            json.put("type",transaction.type);
            json.put("nonce",transaction.nonce);
            json.put("fromhash", Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from))));
            json.put("amount",transaction.amount);
            json.put("fee",transaction.getFee());
            json.put("to",Hex.encodeHexString(transaction.to));
            if(transaction.payload==null){
                json.putNull("payload");
            }else{
                json.put("payload",Hex.encodeHexString(transaction.payload));
            }
            json.put("state",transPool.getState());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date date = new Date(transPool.getDatetime());
            json.put("datatime",sdf.format(date));
            jsonArray.add(json);
        }
        return jsonArray;
    }
}
