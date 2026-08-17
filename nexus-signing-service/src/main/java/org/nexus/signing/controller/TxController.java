package org.nexus.signing.controller;

import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.common.tracing.BusinessSpan;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import org.nexus.sdk.common.APIResult;
import org.nexus.signing.pool.NoncePool;
import org.nexus.signing.pool.NonceState;
import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.sdk.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.tracing.Tracer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * 签名服务交易控制器。
 *
 * <p>从 {@code org.nexus.wallet.signing.controller.TxController}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.controller}。</p>
 *
 * <p>P3-T5：在签名 + 广播链路添加业务 span（signing.broadcast），
 * span 树结构见 docs/tracing-business-span.md。</p>
 *
 * <p>提供链上转账签名 + 广播 REST 端点：
 * <ul>
 *   <li>{@code POST /ClientToTransferAccount}：legacy 签名广播端点</li>
 *   <li>{@code POST /api/v1/transfers/sign}：合约化签名广播端点</li>
 *   <li>{@code GET /getNoncePool}：查询 Nonce 池</li>
 * </ul></p>
 */
@RestController
public class TxController {

    @Autowired
    NoncePool noncePool;

    @Autowired
    NodeController nodeController;

    @Autowired
    PlatformKeystore platformKeystore;

    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    @Autowired(required = false)
    Tracer tracer;

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
     *
     * <p>SECURITY (P1-F1): 端点强制 {@code ROLE_SIGNING_SERVICE} 鉴权，
     * 仅允许持有合法 JWT 且 roles 含 {@code SIGNING_SERVICE} 的调用方
     * （即 gateway 通过 Feign 注入服务间 token）访问。</p>
     */
    @PreAuthorize("hasRole('SIGNING_SERVICE')")
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
     *
     * <p>SECURITY (P1-F1): 端点强制 {@code ROLE_SIGNING_SERVICE} 鉴权。
     * gateway 通过 {@code FeignJwtRequestInterceptor} 在 Feign 调用前
     * 注入 {@code Authorization: Bearer <jwt>}，token 由共享
     * {@code JWT_SECRET} 签发，roles 含 {@code SIGNING_SERVICE}。</p>
     */
    @PreAuthorize("hasRole('SIGNING_SERVICE')")
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
        // P3-T5：签名 + 广播 span（signing.broadcast）
        try (BusinessSpan span = BusinessSpan.start(tracer, "signing.broadcast")
                .attr("signing.from.pubkey", fromPubkey)
                .attr("signing.to.pubkey.hash", toPubkeyHash)
                .attr("signing.amount", amount)) {
            String prikey = platformKeystore == null ? null : platformKeystore.getPrikey();
            if (prikey == null || prikey.isBlank()) {
                span.attr("signing.error", "no_signing_key").error(null);
                return fail("No signing key available: wallet.keystore.json is not configured");
            }
            String platformPubkey = platformKeystore.getPubkey();
            if (platformPubkey == null || platformPubkey.isBlank()
                    || !platformPubkey.equalsIgnoreCase(fromPubkey)) {
                span.attr("signing.error", "pubkey_mismatch").error(null);
                return fail("fromPubkey does not match the platform keystore public key; "
                        + "caller-supplied private keys are no longer accepted");
            }

            long nownonce=0;
            String frompubhash=WalletUtils.pubkeyStrToPubkeyHashStr(fromPubkey);
            String address=WalletUtils.pubkeyHashToAddress(frompubhash);
            if(WalletUtils.verifyAddress(address)!=0){
                span.attr("signing.error", "address_invalid").error(null);
                return fail("Address Error");
            }
            span.attr("signing.from.address", address);
            long maxnonce=noncePool.getMaxNonce(address);
            if(maxnonce==0){
                //rpc获取nonce
                JsonObject getnonoce=nodeController.getNonce(frompubhash);
                int Code= getnonoce != null && getnonoce.has("code") ? getnonoce.get("code").getAsInt() : 0;
                if(Code==5000){
                    span.attr("signing.error", "nonce_fetch_failed").error(null);
                    return fail("Error");
                }
                long dbnonce= getnonoce != null && getnonoce.has("data") ? getnonoce.get("data").getAsLong() : 0;
                nownonce=dbnonce;
            }else{
                nownonce=maxnonce;
            }
            span.attr("signing.nonce", nownonce);
            ObjectNode data = TxUtils.ClientToTransferAccount(fromPubkey,toPubkeyHash,amount,prikey,nownonce);
            if (data == null || data.isEmpty() || !data.has("data")){
                span.attr("signing.error", "tx_build_failed").error(null);
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
                span.attr("signing.tx.hash", texhash).success();
                return data;
            }
        }
    }

    /** Build a 5000-status error payload (same shape as the legacy API). */
    private Object fail(String message) {
        APIResult result = new APIResult();
        result.setStatusCode(5000);
        result.setMessage(message);
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    /**
     * 查询指定地址的 Nonce 池快照。
     *
     * <p>SECURITY (P1-F1): 端点强制 {@code ROLE_SIGNING_SERVICE} 鉴权，
     * 与签名端点同权限级别（Nonce 池暴露可辅助构造交易）。</p>
     */
    @PreAuthorize("hasRole('SIGNING_SERVICE')")
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