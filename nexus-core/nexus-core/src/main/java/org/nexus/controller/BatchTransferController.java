package org.nexus.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.nexus.service.BatchTransferService;

import java.util.List;

/**
 * 批量转账 RPC 控制器。
 *
 * <p>提供批量转账的 HTTP RPC 接口，允许在单笔交易中向多个收款人转账。
 * payload 格式为 JSON 数组，每个元素包含 {@code address}（收款地址）和
 * {@code amount}（转账金额）。</p>
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
@RequestMapping("/batch")
public class BatchTransferController {

    @Autowired
    private BatchTransferService batchTransferService;

    /**
     * 提交批量转账交易。
     *
     * <p>请求体为 JSON 数组格式，每个元素包含收款地址和金额：
     * <pre>
     * [
     *   {"address":"NEX1abc...", "amount":1000},
     *   {"address":"NEX2def...", "amount":2000}
     * ]
     * </pre></p>
     *
     * @param items 转账项列表，每项包含 address 和 amount
     * @param fromPubkey 发起方公钥（十六进制字符串，32 字节）
     * @param prikey 发起方私钥（十六进制字符串，32 字节）
     * @param nonce 交易 nonce
     * @return 统一响应格式，data 中包含交易哈希和总金额
     */
    @PostMapping("/transfer")
    public Object batchTransfer(@RequestBody List<TransferItem> items,
                                @RequestParam(value = "fromPubkey", required = false) String fromPubkey,
                                @RequestParam(value = "prikey", required = false) String prikey,
                                @RequestParam(value = "nonce", required = false, defaultValue = "0") long nonce) {
        return batchTransferService.batchTransfer(fromPubkey, items, prikey, nonce);
    }

    /**
     * 查询批量转账状态。
     *
     * @param txHash 交易哈希（URL 路径参数，十六进制字符串）
     * @return 统一响应格式，data 中包含交易状态信息
     */
    @GetMapping("/status/{txHash}")
    public Object getBatchStatus(@PathVariable("txHash") String txHash) {
        return batchTransferService.getBatchStatus(txHash);
    }
}
