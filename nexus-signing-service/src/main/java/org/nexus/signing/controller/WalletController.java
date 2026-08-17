package org.nexus.signing.controller;

import org.nexus.sdk.util.JsonUtil;
import java.util.HashMap;

import org.nexus.sdk.wallet.WalletUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.sdk.common.APIResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 钱包工具控制器（无状态钱包工具端点）。
 *
 * <p>从 {@code org.nexus.wallet.signing.controller.WalletController}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.controller}。</p>
 *
 * <p>提供地址校验、keystore 转换、密码派生等无状态钱包工具端点。
 * 不涉及私钥托管，仅依赖 {@link WalletUtils} 的纯计算方法。</p>
 *
 * <p>SECURITY (P1-F1): 所有端点受 {@code SecurityConfig#anyRequest().authenticated()}
 * 强制 JWT 鉴权。其中 {@code /obtainPrikey} 端点会返回明文私钥，额外强制
 * {@code ROLE_ADMIN}（默认拒绝——无 ADMIN token 签发路径，相当于端点下线）。
 * 其他 keystore 解密类端点（{@code /keystoreToPubkey}、{@code /keystoreToPubkeyHash}、
 * {@code /keystoreToAddress}）同样标记 {@code ROLE_ADMIN}，避免 keystore+password
 * 组合泄露公钥/地址后辅助破解私钥。</p>
 */
@RestController
public class WalletController {
    /**
     * SECURITY (P1-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * SECURITY (P1-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * SECURITY (P1-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * SECURITY (P1-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * SECURITY (P1-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * SECURITY (P1-F1): 端点会返回明文私钥，强制 {@code ROLE_ADMIN}。
     * <p>默认拒绝：无 ADMIN token 签发路径，相当于端点下线。
     * 紧急运维场景需通过专用离线流程签发短期 ADMIN token 后访问。</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * SECURITY (P1-F1): 输入明文私钥，强制 {@code ROLE_ADMIN} 避免私钥泄露辅助攻击。
     */
    @PreAuthorize("hasRole('ADMIN')")
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