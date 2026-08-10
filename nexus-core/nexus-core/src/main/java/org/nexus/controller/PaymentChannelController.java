package org.nexus.controller;

import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.service.ChannelSettlementService;
import org.nexus.service.PaymentChannelService;

import java.util.Map;

/**
 * 支付通道 RPC 控制器。
 *
 * <p>提供支付通道的 HTTP RPC 接口，包括通道开启、关闭、状态查询、
 * 地址关联通道查询以及通道结算（协作关闭、争议结算、强制过期）。
 * 通道基于 NEX 代币实现双向链下支付。</p>
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
@RequestMapping("/channel")
public class PaymentChannelController {

    @Autowired
    private PaymentChannelService channelService;

    @Autowired
    private ChannelSettlementService settlementService;

    /**
     * 开启支付通道。
     *
     * <p>创建一个新的双向支付通道，指定两个参与方和初始注资金额。
     * 通道开启后进入 {@link PaymentChannel.State#OPEN} 状态。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code from} - 发起方地址</li>
     *                  <li>{@code to} - 对方地址</li>
     *                  <li>{@code amount} - 注资金额（NEX 最小单位）</li>
     *                  <li>{@code lockTime} - 通道锁定时间（秒）</li>
     *                </ul>
     * @return 统一响应格式，data 中包含新创建的通道信息
     */
    @PostMapping("/open")
    public Object openChannel(@RequestBody Map<String, Object> request) {
        String from = (String) request.get("from");
        String to = (String) request.get("to");
        Object amountObj = request.get("amount");
        Object lockTimeObj = request.get("lockTime");

        long amount = amountObj != null ? Long.parseLong(amountObj.toString()) : 0L;
        int lockTime = lockTimeObj != null ? Integer.parseInt(lockTimeObj.toString()) : 0;

        return channelService.openChannel(from, to, amount, lockTime);
    }

    /**
     * 关闭支付通道。
     *
     * <p>提交通道最终结算状态并触发关闭流程。关闭后进入争议期，
     * 争议期结束后通道最终结算。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code channelId} - 通道 ID</li>
     *                  <li>{@code finalBalance1} - 参与方一最终余额</li>
     *                  <li>{@code finalBalance2} - 参与方二最终余额</li>
     *                  <li>{@code nonce} - 最终状态 nonce</li>
     *                </ul>
     * @return 统一响应格式，data 中包含关闭状态
     */
    @PostMapping("/close")
    public Object closeChannel(@RequestBody Map<String, Object> request) {
        String channelId = (String) request.get("channelId");
        Object balance1Obj = request.get("finalBalance1");
        Object balance2Obj = request.get("finalBalance2");
        Object nonceObj = request.get("nonce");

        long finalBalance1 = balance1Obj != null ? Long.parseLong(balance1Obj.toString()) : 0L;
        long finalBalance2 = balance2Obj != null ? Long.parseLong(balance2Obj.toString()) : 0L;
        long nonce = nonceObj != null ? Long.parseLong(nonceObj.toString()) : 0L;

        return channelService.closeChannel(channelId, finalBalance1, finalBalance2, nonce);
    }

    /**
     * 查询通道状态。
     *
     * @param channelId 通道 ID（URL 路径参数）
     * @return 统一响应格式，data 中包含 {@link PaymentChannel} 对象
     */
    @GetMapping("/state/{channelId}")
    public Object getChannelState(@PathVariable("channelId") String channelId) {
        return channelService.getChannelState(channelId);
    }

    /**
     * 查询地址关联的通道列表。
     *
     * @param address NEX 地址（URL 路径参数）
     * @return 统一响应格式，data 中包含通道列表
     */
    @GetMapping("/list/{address}")
    public Object listChannelsByAddress(@PathVariable("address") String address) {
        return channelService.listChannelsByAddress(address);
    }

    // ==================== Settlement Endpoints ====================

    /**
     * 协作关闭支付通道。
     *
     * <p>双方签名同意最终余额后，直接结算通道资金。需要双方对
     * channelId + nonce + finalBalance1 + finalBalance2 的 Ed25519 签名。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code channelId} - 通道 ID</li>
     *                  <li>{@code finalBalance1} - 参与方一最终余额</li>
     *                  <li>{@code finalBalance2} - 参与方二最终余额</li>
     *                  <li>{@code nonce} - 最终状态 nonce</li>
     *                  <li>{@code sig1} - 参与方一签名（十六进制）</li>
     *                  <li>{@code sig2} - 参与方二签名（十六进制）</li>
     *                  <li>{@code fromPubkey1} - 参与方一公钥（十六进制）</li>
     *                  <li>{@code fromPubkey2} - 参与方二公钥（十六进制）</li>
     *                </ul>
     * @return 统一响应格式，data 中包含结算详情
     */
    @PostMapping("/cooperative-close")
    public Object cooperativeClose(@RequestBody Map<String, Object> request) {
        String channelId = (String) request.get("channelId");
        long finalBalance1 = parseLong(request.get("finalBalance1"));
        long finalBalance2 = parseLong(request.get("finalBalance2"));
        long nonce = parseLong(request.get("nonce"));
        String sig1Hex = (String) request.get("sig1");
        String sig2Hex = (String) request.get("sig2");
        String pubkey1Hex = (String) request.get("fromPubkey1");
        String pubkey2Hex = (String) request.get("fromPubkey2");

        byte[] sig1 = decodeHex(sig1Hex);
        byte[] sig2 = decodeHex(sig2Hex);
        byte[] pubkey1 = decodeHex(pubkey1Hex);
        byte[] pubkey2 = decodeHex(pubkey2Hex);

        return settlementService.cooperativeClose(channelId, finalBalance1, finalBalance2,
                nonce, sig1, sig2, pubkey1, pubkey2);
    }

    /**
     * 结算争议通道。
     *
     * <p>争议期过后，按最高 nonce 的 update 结算通道资金。</p>
     *
     * @param channelId 通道 ID（URL 路径参数）
     * @param request   请求体，包含以下字段：
     *                  <ul>
     *                    <li>{@code currentBlockHeight} - 当前区块高度</li>
     *                  </ul>
     * @return 统一响应格式，data 中包含结算详情
     */
    @PostMapping("/settle/{channelId}")
    public Object settleChannel(@PathVariable("channelId") String channelId,
                                @RequestBody Map<String, Object> request) {
        long currentBlockHeight = parseLong(request.get("currentBlockHeight"));
        return settlementService.settleChannel(channelId, currentBlockHeight);
    }

    /**
     * 查询结算状态。
     *
     * @param channelId 通道 ID（URL 路径参数）
     * @return 统一响应格式，data 中包含结算结果或通道当前状态
     */
    @GetMapping("/settlement/{channelId}")
    public Object getSettlementStatus(@PathVariable("channelId") String channelId) {
        return settlementService.getSettlementStatus(channelId);
    }

    /**
     * 强制过期关闭通道。
     *
     * <p>通道锁定期到期后，按当前余额分配资金并标记为 EXPIRED。</p>
     *
     * @param channelId 通道 ID（URL 路径参数）
     * @param request   请求体，包含以下字段：
     *                  <ul>
     *                    <li>{@code currentBlockHeight} - 当前区块高度</li>
     *                  </ul>
     * @return 统一响应格式，data 中包含结算详情
     */
    @PostMapping("/force-expire/{channelId}")
    public Object forceExpire(@PathVariable("channelId") String channelId,
                              @RequestBody Map<String, Object> request) {
        long currentBlockHeight = parseLong(request.get("currentBlockHeight"));
        return settlementService.forceExpire(channelId, currentBlockHeight);
    }

    /**
     * 单方关闭支付通道。
     *
     * <p>一方提交最终状态，发起争议流程，等待争议期后结算。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code channelId} - 通道 ID</li>
     *                  <li>{@code nonce} - 最终状态 nonce</li>
     *                  <li>{@code balance1} - 参与方一余额</li>
     *                  <li>{@code balance2} - 参与方二余额</li>
     *                  <li>{@code sig1} - 参与方一签名（十六进制）</li>
     *                  <li>{@code sig2} - 参与方二签名（十六进制）</li>
     *                  <li>{@code timestamp} - 时间戳（毫秒，可选）</li>
     *                  <li>{@code closerPubkey} - 发起方公钥（十六进制）</li>
     *                  <li>{@code closerSig} - 发起方签名（十六进制）</li>
     *                </ul>
     * @return 统一响应格式，data 中包含争议期信息
     */
    @PostMapping("/unilateral-close")
    public Object unilateralClose(@RequestBody Map<String, Object> request) {
        String channelId = (String) request.get("channelId");
        long nonce = parseLong(request.get("nonce"));
        long balance1 = parseLong(request.get("balance1"));
        long balance2 = parseLong(request.get("balance2"));
        String sig1Hex = (String) request.get("sig1");
        String sig2Hex = (String) request.get("sig2");
        Object timestampObj = request.get("timestamp");
        long timestamp = timestampObj != null ? parseLong(timestampObj) : System.currentTimeMillis();
        String closerPubkeyHex = (String) request.get("closerPubkey");
        String closerSigHex = (String) request.get("closerSig");

        byte[] sig1 = decodeHex(sig1Hex);
        byte[] sig2 = decodeHex(sig2Hex);
        byte[] closerPubkey = decodeHex(closerPubkeyHex);
        byte[] closerSig = decodeHex(closerSigHex);

        ChannelUpdate latestUpdate = new ChannelUpdate(
                channelId, nonce, balance1, balance2, sig1, sig2, timestamp
        );

        return settlementService.unilateralClose(channelId, latestUpdate, closerPubkey, closerSig);
    }

    // ==================== Private Helpers ====================

    /**
     * 安全解析长整型参数。
     *
     * @param value 请求参数对象
     * @return 解析后的 long 值，null 返回 0
     */
    private long parseLong(Object value) {
        return value != null ? Long.parseLong(value.toString()) : 0L;
    }

    /**
     * 安全解码十六进制字符串为字节数组。
     *
     * @param hex 十六进制字符串，可为 null
     * @return 解码后的字节数组，null 或解码失败时返回 null
     */
    private byte[] decodeHex(String hex) {
        try {
            return hex != null ? Hex.decodeHex(hex) : null;
        } catch (org.apache.commons.codec.DecoderException e) {
            return null;
        }
    }
}
