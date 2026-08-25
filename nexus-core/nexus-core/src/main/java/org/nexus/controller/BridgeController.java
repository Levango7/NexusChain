package org.nexus.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.payment.BridgeTransaction;
import org.nexus.service.BridgeService;

import java.util.List;
import java.util.Map;

/**
 * 跨链桥 RPC 控制器。
 *
 * <p>提供 NEX 跨链桥的 HTTP RPC 接口，支持资产在不同链之间的转移。
 * 跨链流程为：源链锁定（LOCK） -> 目标链铸造（MINT），
 * 反向流程为：目标链销毁（BURN） -> 源链解锁（UNLOCK）。</p>
 *
 * <p>所有接口统一返回 {@link APIResult} 格式：
 * <pre>
 * {"message":"", "data":[], "code":2000}
 * </pre></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@RestController
@RequestMapping("/bridge")
public class BridgeController {

    @Autowired
    private BridgeService bridgeService;

    /** 单笔跨链交易金额上限（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.bridge.single-tx-limit:1000000000}")
    private long singleTxLimit;

    /** 每日跨链交易总额上限（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.bridge.daily-limit:10000000000}")
    private long dailyLimit;

    /** 时间锁持续时间（秒），通过配置注入。 */
    @Value("${nexus.bridge.timelock-duration:3600}")
    private long timelockDuration;

    /** 最低验证人签名数，通过配置注入。 */
    @Value("${nexus.bridge.min-validators:3}")
    private int minValidators;

    /**
     * 锁定资产。
     *
     * <p>在源链上锁定 NEX 代币，为跨链转账做准备。锁定后等待验证人确认
     * 并在目标链上铸造对应资产。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code from} - 锁定发起方地址（或公钥十六进制）</li>
     *                  <li>{@code amount} - 锁定金额（NEX 最小单位）</li>
     *                  <li>{@code targetChain} - 目标链标识</li>
     *                  <li>{@code recipient} - 目标链收款人地址</li>
     *                </ul>
     * @param fromPubkey 锁定发起方公钥（十六进制字符串，32 字节），可选
     * @param prikey 发起方私钥（十六进制字符串，32 字节），可选
     * @param nonce 交易 nonce
     * @return 统一响应格式，data 中包含桥交易 ID 和锁定状态
     */
    @PostMapping("/lock")
    public Object lock(@RequestBody Map<String, Object> request,
                       @RequestParam(value = "fromPubkey", required = false) String fromPubkey,
                       @RequestParam(value = "prikey", required = false) String prikey,
                       @RequestParam(value = "nonce", required = false, defaultValue = "0") long nonce) {
        String pubkey = fromPubkey != null ? fromPubkey : (String) request.get("from");
        Object amountObj = request.get("amount");
        String targetChain = (String) request.get("targetChain");
        String recipient = (String) request.get("recipient");

        long amount = amountObj != null ? Long.parseLong(amountObj.toString()) : 0L;

        // 本地金额限制校验
        if (amount > singleTxLimit) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "amount " + amount + " exceeds single transaction limit " + singleTxLimit);
        }

        return bridgeService.lock(pubkey, targetChain, recipient, amount, prikey, nonce);
    }

    /**
     * 在目标链上铸造资产。
     *
     * <p>验证人在确认源链锁定交易后，在目标链上铸造对应资产。
     * 须满足多签验证和时间锁要求。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code bridgeTxId} - 桥交易 ID</li>
     *                  <li>{@code amount} - 铸造金额</li>
     *                  <li>{@code recipient} - 收款人地址</li>
     *                  <li>{@code sourceChain} - 源链标识</li>
     *                  <li>{@code signatures} - 验证人签名数组（对规范化 messageHash 的 Ed25519 签名）</li>
     *                  <li>{@code pubkeys} - 可选，验证人公钥数组（与 signatures 一一对应；
     *                      提供时走 v1.9.4 显式公钥路径，缺省时由 ValidatorRegistry 解析归属）</li>
     *                </ul>
     * @return 统一响应格式，data 中包含铸造结果
     */
    @PostMapping("/mint")
    public Object mint(@RequestBody Map<String, Object> request) {
        String bridgeTxId = (String) request.get("bridgeTxId");
        Object amountObj = request.get("amount");
        String recipient = (String) request.get("recipient");
        String sourceChain = (String) request.get("sourceChain");
        @SuppressWarnings("unchecked")
        List<String> signatures = (List<String>) request.get("signatures");
        @SuppressWarnings("unchecked")
        List<String> pubkeys = (List<String>) request.get("pubkeys");

        long amount = amountObj != null ? Long.parseLong(amountObj.toString()) : 0L;

        if (pubkeys != null && !pubkeys.isEmpty()) {
            return bridgeService.mint(bridgeTxId, sourceChain, recipient, amount, pubkeys, signatures);
        }
        return bridgeService.mint(bridgeTxId, sourceChain, recipient, amount, signatures);
    }

    /**
     * 销毁目标链上的资产。
     *
     * <p>在目标链上销毁跨链铸造的资产，为反向解锁做准备。
     * 须满足多签验证和时间锁要求。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code from} - 销毁发起方地址（或公钥十六进制）</li>
     *                  <li>{@code amount} - 销毁金额</li>
     *                  <li>{@code targetChain} - 目标链标识</li>
     *                </ul>
     * @param fromPubkey 销毁发起方公钥（十六进制字符串，32 字节），可选
     * @param prikey 发起方私钥（十六进制字符串，32 字节），可选
     * @param nonce 交易 nonce
     * @return 统一响应格式，data 中包含销毁结果
     */
    @PostMapping("/burn")
    public Object burn(@RequestBody Map<String, Object> request,
                       @RequestParam(value = "fromPubkey", required = false) String fromPubkey,
                       @RequestParam(value = "prikey", required = false) String prikey,
                       @RequestParam(value = "nonce", required = false, defaultValue = "0") long nonce) {
        String pubkey = fromPubkey != null ? fromPubkey : (String) request.get("from");
        Object amountObj = request.get("amount");
        String targetChain = (String) request.get("targetChain");

        long amount = amountObj != null ? Long.parseLong(amountObj.toString()) : 0L;

        return bridgeService.burn(pubkey, targetChain, amount, prikey, nonce);
    }

    /**
     * 查询桥交易状态。
     *
     * @param txHash 交易哈希或桥交易 ID（URL 路径参数）
     * @return 统一响应格式，data 中包含 {@link BridgeTransaction} 对象
     */
    @GetMapping("/status/{txHash}")
    public Object getBridgeStatus(@PathVariable("txHash") String txHash) {
        return bridgeService.getStatus(txHash);
    }

    /**
     * 查询当前桥交易限额。
     *
     * @return 统一响应格式，data 中包含限额配置信息
     */
    @GetMapping("/limit")
    public Object getBridgeLimit() {
        return bridgeService.getLimit();
    }
}
