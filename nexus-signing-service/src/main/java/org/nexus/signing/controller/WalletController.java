package org.nexus.signing.controller;

import org.nexus.sdk.util.JsonUtil;
import java.util.HashMap;

import org.nexus.sdk.wallet.WalletUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.sdk.common.APIResult;
import org.nexus.signing.config.SecurityRoles;
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
 * <p>SECURITY (P2-F1 完整安全架构):
 * <ul>
 *   <li>所有端点受 {@code SecurityConfig#anyRequest().authenticated()}
 *       强制 JWT 鉴权</li>
 *   <li>keystore 解密类端点（{@code /fromPassword}、{@code /modifyPassword}、
 *       {@code /keystoreToAddress}、{@code /keystoreToPubkey}、
 *       {@code /keystoreToPubkeyHash}、{@code /prikeyToPubkey}）强制
 *       {@code ROLE_ADMIN}，避免 keystore+password 组合泄露公钥/地址后
 *       辅助破解私钥</li>
 *   <li>无状态钱包工具端点（{@code /verifyAddress}、{@code /pubkeyHashToAddress}、
 *       {@code /addressToPubkeyHash}、{@code /pubkeyStrToPubkeyHashStr}）强制
 *       {@code ROLE_READ}，仅供只读查询</li>
 *   <li>{@code /obtainPrikey} 端点已彻底下线（P2-F1）：方法保留但移除
 *       {@code @RequestMapping} 注解，不再暴露为 REST 端点。
 *       明文私钥导出仅可通过专用离线流程在 OS 层面执行，
 *       不再经 HTTP 接口暴露</li>
 * </ul></p>
 */
@RestController
public class WalletController {
    /**
     * SECURITY (P2-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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
     * SECURITY (P2-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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

    /**
     * SECURITY (P2-F1): 无状态地址校验工具，强制 {@code ROLE_READ}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.READ + "')")
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


    /**
     * SECURITY (P2-F1): 无状态地址转换工具，强制 {@code ROLE_READ}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.READ + "')")
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
    @PreAuthorize("hasRole('" + SecurityRoles.READ + "')")
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
     * SECURITY (P2-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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
     * SECURITY (P2-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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
     * SECURITY (P2-F1): keystore+password 解密类操作，强制 {@code ROLE_ADMIN}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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
     * SECURITY (P2-F1): 明文私钥导出端点已彻底下线。
     *
     * <p>本方法保留供内部代码引用（如 {@link org.nexus.signing.keystore.PlatformKeystore}
     * 在启动时通过 {@link WalletUtils#obtainPrikey} 加载平台 keystore），
     * 但<b>不再暴露为 REST 端点</b>：移除了原 {@code @RequestMapping} 注解，
     * Spring MVC 不会路由任何 HTTP 请求到此方法。</p>
     *
     * <p>历史背景：P1-F1 阶段本端点强制 {@code ROLE_ADMIN} 作为临时缓解措施；
     * P2-F1 阶段按「最小暴露面」原则彻底下线，明文私钥导出仅可通过：
     * <ul>
     *   <li>专用离线 CLI 工具（OS 层面操作，全程审计）</li>
     *   <li>紧急运维场景的临时端口转发 + mTLS 双向认证（不在本服务暴露）</li>
     * </ul></p>
     *
     * @deprecated 不再作为 REST 端点暴露，保留方法仅为内部调用兼容
     */
    @Deprecated
    Object obtainPrikey(@RequestParam(value = "keystoreJson", required = true) String keystoreJson,
                        @RequestParam(value = "password", required = true) String password) {
        // 不再经 HTTP 暴露；如内部代码需获取私钥，请直接调用 WalletUtils.obtainPrikey
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
     * SECURITY (P2-F1): 输入明文私钥，强制 {@code ROLE_ADMIN} 避免私钥泄露辅助攻击。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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

    /**
     * SECURITY (P2-F1): 无状态 pubkey→pubkeyHash 转换工具，强制 {@code ROLE_READ}。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.READ + "')")
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