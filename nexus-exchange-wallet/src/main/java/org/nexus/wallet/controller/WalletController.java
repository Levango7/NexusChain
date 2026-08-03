package org.nexus.wallet.controller;

import org.nexus.wallet.util.JsonUtil;
import java.util.HashMap;

import org.nexus.sdk.wallet.WalletUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.wallet.ApiResult.APIResult;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
public class WalletController {
    @RequestMapping(value="/fromPassword",method = RequestMethod.GET )
    public Object fromPassword(@RequestParam(value = "password", required = true) String password) {
        ObjectNode keystore = WalletUtils.fromPassword(password);
        APIResult result = new APIResult();
        if(keystore == null || keystore.isEmpty()){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(keystore);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/modifyPassword",method = RequestMethod.POST )
    public Object modifyPassword(@RequestParam(value = "keystoreJson", required = true) String keystoreJson,@RequestParam(value = "password", required = true) String password,@RequestParam(value = "newPassword", required = true) String newPassword) {
        ObjectNode keystore = WalletUtils.modifyPassword(keystoreJson,password,newPassword);
        APIResult result = new APIResult();
        if(keystore == null || keystore.isEmpty()){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(keystore);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/verifyAddress",method = RequestMethod.GET )
    public Object verifyAddress(@RequestParam(value = "address", required = true) String address) {
        int code = WalletUtils.verifyAddress(address);
        System.out.println(address);
        System.out.println(code);
        APIResult result = new APIResult();
        if (code == 0){
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
        }else if(code == -1){
            result.setStatusCode(6000);
            result.setMessage("Error");
        }else if(code == -2){
            result.setStatusCode(7000);
            result.setMessage("Error");
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }


    @RequestMapping(value="/pubkeyHashToAddress",method = RequestMethod.GET )
    public Object pubkeyHashToAddress(@RequestParam(value = "pubkeyHash", required = true) String pubkeyHash) {
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);
        APIResult result = new APIResult();
        if(address == null || address == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(address);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    /**
     * 调用此方法之前请先校验地址合法性！(调用verifyAddress方法)
     * @param address
     * @return
     */
    @RequestMapping(value="/addressToPubkeyHash",method = RequestMethod.GET )
    public Object addressToPubkeyHash(@RequestParam(value = "address", required = true) String address) {
        String pubkeyHash = WalletUtils.addressToPubkeyHash(address);
        APIResult result = new APIResult();
        if(pubkeyHash == null || pubkeyHash == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(pubkeyHash);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/keystoreToAddress",method = RequestMethod.POST )
    public Object keystoreToAddress(@RequestParam(value = "keystoreJson", required = true) String keystoreJson){
        String address = WalletUtils.keystoreToAddress(keystoreJson,null);
        APIResult result = new APIResult();
        if(address == null || address == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(address);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/keystoreToPubkey",method = RequestMethod.POST )
    public Object keystoreToPubkey(@RequestParam(value = "keystoreJson", required = true) String keystoreJson, @RequestParam(value = "password", required = true) String password) {
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson,password);
        APIResult result = new APIResult();
        if(pubkey == null || pubkey == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(pubkey);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/keystoreToPubkeyHash",method = RequestMethod.POST )
    public Object keystoreToPubkeyHash(@RequestParam(value = "keystoreJson", required = true) String keystoreJson, @RequestParam(value = "password", required = true) String password) {
        String pubkeyHash = WalletUtils.keystoreToPubkeyHash(keystoreJson,password);
        APIResult result = new APIResult();
        if(pubkeyHash == null || pubkeyHash == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(pubkeyHash);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/obtainPrikey",method = RequestMethod.POST )
    public Object obtainPrikey(@RequestParam(value = "keystoreJson", required = true) String keystoreJson,@RequestParam(value = "password", required = true) String password) {
        String privateKey = WalletUtils.obtainPrikey(keystoreJson,password);
        APIResult result = new APIResult();
        if(privateKey == null || privateKey == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(privateKey);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);

    }

    @RequestMapping(value="/prikeyToPubkey",method = RequestMethod.POST )
    public Object prikeyToPubkey(@RequestParam(value = "prikey", required = true) String prikey) {
        String privateKey = WalletUtils.prikeyToPubkey(prikey);
        APIResult result = new APIResult();
        if(privateKey == null || privateKey == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(privateKey);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    @RequestMapping(value="/pubkeyStrToPubkeyHashStr",method = RequestMethod.POST )
    public Object pubkeyToPubkeyHash(@RequestParam(value = "pubkey", required = true) String pubkey) {
        String pubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        APIResult result = new APIResult();
        if(pubkeyHash == null || pubkeyHash == ""){
            result.setStatusCode(5000);
            result.setMessage("Error");
        }else{
            result.setStatusCode(2000);
            result.setMessage("SUCCESS");
            result.setData(pubkeyHash);
        }
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }
}
