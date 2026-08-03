package org.nexus.wallet.controller;

import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import org.nexus.wallet.ApiResult.APIResult;
import org.nexus.wallet.pool.NoncePool;
import org.nexus.wallet.pool.NonceState;
import org.nexus.wallet.keystore.PlatformKeystore;
import org.nexus.wallet.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

@RestController
public class TxController {

    @Autowired
    NoncePool noncePool;

    @Autowired
    NodeController nodeController;

    @Autowired
    PlatformKeystore platformKeystore;

    @RequestMapping(value="/ClientToTransferAccount",method = RequestMethod.POST )
    public Object ClientToTransferAccount(@RequestParam(value = "fromPubkey", required = true) String fromPubkey,@RequestParam(value = "toPubkeyHash", required = true) String toPubkeyHash,
                                        @RequestParam(value = "amount", required = true) BigDecimal amount,@RequestParam(value = "prikey", required = true) String prikey
                                        ) throws IOException {
        long nownonce=0;
        String frompubhash=WalletUtils.pubkeyStrToPubkeyHashStr(fromPubkey);
        String address=WalletUtils.pubkeyHashToAddress(frompubhash);
        if(WalletUtils.verifyAddress(address)!=0){
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("Address Error");
            return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
        }
        long maxnonce=noncePool.getMaxNonce(address);
        if(maxnonce==0){
            //rpc获取nonce
            JsonObject getnonoce=nodeController.getNonce(frompubhash);
            int Code= getnonoce != null && getnonoce.has("code") ? getnonoce.get("code").getAsInt() : 0;
            if(Code==5000){
                APIResult result = new APIResult();
                result.setStatusCode(5000);
                result.setMessage("Error");
                return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
            }
            long dbnonce= getnonoce != null && getnonoce.has("data") ? getnonoce.get("data").getAsLong() : 0;
            nownonce=dbnonce;
        }else{
            nownonce=maxnonce;
        }
        ObjectNode data = TxUtils.ClientToTransferAccount(fromPubkey,toPubkeyHash,amount,prikey,nownonce);
        if (data == null || data.isEmpty()){
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("Error");
            return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
        }else {
            Map<String,Object> dataMap = JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(data), HashMap.class);
            dataMap.put("statusCode",2000);
            String texhash= (String) dataMap.get("data");
            nownonce++;
            NonceState nonceState=new NonceState(texhash,nownonce,new Date().getTime());
            noncePool.add(address,nonceState);
            return dataMap;
        }
    }

    /**
     * Sign + broadcast a transfer using the SERVER-SIDE platform keystore, so the
     * caller (e.g. the gateway) never transmits a private key. This is the endpoint
     * the gateway delegates to (ExchangeWalletClient.signTransfer). A caller-supplied
     * keystore ({@code keystoreJson}/{@code password}) still overrides when provided,
     * preserving backward compatibility with {@code /ClientToTransferAccount}.
     */
    @RequestMapping(value="/api/v1/transfers/sign", method = RequestMethod.POST )
    public Object signTransfer(@RequestParam(value = "fromPubkey", required = true) String fromPubkey,
                               @RequestParam(value = "toPubkeyHash", required = true) String toPubkeyHash,
                               @RequestParam(value = "amount", required = true) BigDecimal amount,
                               @RequestParam(value = "keystoreJson", required = false) String keystoreJson,
                               @RequestParam(value = "password", required = false) String password
    ) throws IOException {
        String prikey;
        if (keystoreJson != null && !keystoreJson.isBlank()) {
            prikey = WalletUtils.obtainPrikey(keystoreJson, password);
        } else {
            prikey = platformKeystore.getPrikey();
        }
        if (prikey == null || prikey.isBlank()) {
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("No signing key available");
            return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
        }

        long nownonce = 0;
        String frompubhash = WalletUtils.pubkeyStrToPubkeyHashStr(fromPubkey);
        String address = WalletUtils.pubkeyHashToAddress(frompubhash);
        if (WalletUtils.verifyAddress(address) != 0) {
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("Address Error");
            return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
        }
        long maxnonce = noncePool.getMaxNonce(address);
        if (maxnonce == 0) {
            JsonObject getnonoce = nodeController.getNonce(frompubhash);
            int Code = getnonoce != null && getnonoce.has("code") ? getnonoce.get("code").getAsInt() : 0;
            if (Code == 5000) {
                APIResult result = new APIResult();
                result.setStatusCode(5000);
                result.setMessage("Error");
                return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
            }
            long dbnonce = getnonoce != null && getnonoce.has("data") ? getnonoce.get("data").getAsLong() : 0;
            nownonce = dbnonce;
        } else {
            nownonce = maxnonce;
        }
        ObjectNode data = TxUtils.ClientToTransferAccount(fromPubkey, toPubkeyHash, amount, prikey, nownonce);
        if (data == null || data.isEmpty()) {
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("Error");
            return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
        } else {
            Map<String, Object> dataMap = JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(data), HashMap.class);
            dataMap.put("statusCode", 2000);
            String texhash = (String) dataMap.get("data");
            nownonce++;
            NonceState nonceState = new NonceState(texhash, nownonce, new Date().getTime());
            noncePool.add(address, nonceState);
            return dataMap;
        }
    }

    @RequestMapping(value="/getNoncePool",method = RequestMethod.GET )
    public Object getNoncePool(@RequestParam(value = "address", required = true) String address){
        if(WalletUtils.verifyAddress(address)!=0){
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("Address Error");
            return result;
        }
        TreeMap<Long, NonceState> tree=noncePool.getTreemap(address);
        return APIResult.newFailResult(2000,"SUCCESS",tree);
    }

}
