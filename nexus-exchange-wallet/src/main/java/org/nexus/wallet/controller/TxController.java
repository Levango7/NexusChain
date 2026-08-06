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
import java.util.TreeMap;

@RestController
public class TxController {

    @Autowired
    NoncePool noncePool;

    @Autowired
    NodeController nodeController;

    @Autowired
    PlatformKeystore platformKeystore;

    /**
     * LEGACY transfer endpoint, kept for backward compatibility with existing
     * form-POST clients.
     *
     * <p>SECURITY (P1 fix): the {@code prikey} request parameter has been
     * REMOVED — caller-supplied plaintext private keys are never accepted.
     * Signing is performed exclusively with the server-side
     * {@link PlatformKeystore}, and {@code fromPubkey} must match the platform
     * keystore public key. Any extra {@code prikey} form field sent by legacy
     * clients is ignored, and requests whose {@code fromPubkey} does not match
     * the platform key are rejected. New clients should use
     * {@code /api/v1/transfers/sign}.</p>
     */
    @RequestMapping(value="/ClientToTransferAccount",method = RequestMethod.POST )
    public Object ClientToTransferAccount(@RequestParam(value = "fromPubkey", required = true) String fromPubkey,
                                          @RequestParam(value = "toPubkeyHash", required = true) String toPubkeyHash,
                                          @RequestParam(value = "amount", required = true) BigDecimal amount
                                          ) throws IOException {
        return signAndBroadcast(fromPubkey, toPubkeyHash, amount);
    }

    /**
     * Sign + broadcast a transfer using the SERVER-SIDE platform keystore, so
     * the caller (e.g. the gateway) never transmits a private key. This is the
     * endpoint the gateway delegates to (ExchangeWalletClient.signTransfer).
     *
     * <p>SECURITY (P1 fix): the former {@code keystoreJson}/{@code password}
     * override has been REMOVED — caller-supplied keystore material is never
     * accepted over HTTP. Signing uses the platform keystore exclusively, and
     * {@code fromPubkey} must match the platform keystore public key.</p>
     */
    @RequestMapping(value="/api/v1/transfers/sign", method = RequestMethod.POST )
    public Object signTransfer(@RequestParam(value = "fromPubkey", required = true) String fromPubkey,
                               @RequestParam(value = "toPubkeyHash", required = true) String toPubkeyHash,
                               @RequestParam(value = "amount", required = true) BigDecimal amount
    ) throws IOException {
        return signAndBroadcast(fromPubkey, toPubkeyHash, amount);
    }

    /**
     * Shared signing pipeline: platform-key-only. Rejects the request unless
     * the platform keystore is loaded and {@code fromPubkey} matches the
     * platform keystore public key. No caller-supplied private key material is
     * ever used.
     */
    private Object signAndBroadcast(String fromPubkey, String toPubkeyHash, BigDecimal amount) throws IOException {
        String prikey = platformKeystore == null ? null : platformKeystore.getPrikey();
        if (prikey == null || prikey.isBlank()) {
            return fail("No signing key available: wallet.keystore.json is not configured");
        }
        String platformPubkey = platformKeystore.getPubkey();
        if (platformPubkey == null || platformPubkey.isBlank()
                || !platformPubkey.equalsIgnoreCase(fromPubkey)) {
            return fail("fromPubkey does not match the platform keystore public key; "
                    + "caller-supplied private keys are no longer accepted");
        }

        long nownonce=0;
        String frompubhash=WalletUtils.pubkeyStrToPubkeyHashStr(fromPubkey);
        String address=WalletUtils.pubkeyHashToAddress(frompubhash);
        if(WalletUtils.verifyAddress(address)!=0){
            return fail("Address Error");
        }
        long maxnonce=noncePool.getMaxNonce(address);
        if(maxnonce==0){
            //rpc获取nonce
            JsonObject getnonoce=nodeController.getNonce(frompubhash);
            int Code= getnonoce != null && getnonoce.has("code") ? getnonoce.get("code").getAsInt() : 0;
            if(Code==5000){
                return fail("Error");
            }
            long dbnonce= getnonoce != null && getnonoce.has("data") ? getnonoce.get("data").getAsLong() : 0;
            nownonce=dbnonce;
        }else{
            nownonce=maxnonce;
        }
        ObjectNode data = TxUtils.ClientToTransferAccount(fromPubkey,toPubkeyHash,amount,prikey,nownonce);
        if (data == null || data.isEmpty() || !data.has("data")){
            return fail("Error");
        }else {
            // 直接返回 ObjectNode（Jackson 原生序列化），不再经 Gson 反射转 HashMap——
            // 旧实现会把 ObjectNode 序列化成 _children/_nodeFactory 内部字段，
            // 导致响应丢失 data（交易哈希）且 noncePool 记录空哈希。
            String texhash = data.get("data").asText();
            data.put("statusCode", 2000);
            nownonce++;
            NonceState nonceState=new NonceState(texhash,nownonce,new Date().getTime());
            noncePool.add(address,nonceState);
            return data;
        }
    }

    /** Build a 5000-status error payload (same shape as the legacy API). */
    private Object fail(String message) {
        APIResult result = new APIResult();
        result.setStatusCode(5000);
        result.setMessage(message);
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
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
