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
import org.nexus.core.payment.StableCoinPosition;
import org.nexus.service.StableCoinService;

import java.util.Map;

/**
 * 稳定币 RPC 控制器。
 *
 * <p>提供 NEX 稳定币系统的 HTTP RPC 接口，包括铸造、赎回、抵押率查询
 * 和价格查询。稳定币通过抵押 NEX 代币铸造，抵押率须满足最低要求。</p>
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
@RequestMapping("/stablecoin")
public class StableCoinController {

    @Autowired
    private StableCoinService stableCoinService;

    /** 最低抵押率，通过配置注入。 */
    @Value("${nexus.stablecoin.collateral-ratio:1.5}")
    private double collateralRatio;

    /** 稳定币符号，通过配置注入。 */
    @Value("${nexus.stablecoin.symbol:NEX}")
    private String symbol;

    /**
     * 铸造稳定币。
     *
     * <p>抵押 NEX 代币铸造稳定币，抵押率须不低于配置的最低抵押率。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code from} - 抵押人地址（或公钥十六进制）</li>
     *                  <li>{@code collateralAmount} - 抵押 NEX 数量（最小单位）</li>
     *                  <li>{@code mintAmount} - 铸造稳定币数量（最小单位）</li>
     *                </ul>
     * @param fromPubkey 抵押人公钥（十六进制字符串，32 字节），可选
     * @param prikey 抵押人私钥（十六进制字符串，32 字节），可选
     * @param nonce 交易 nonce
     * @return 统一响应格式，data 中包含铸造结果和仓位信息
     */
    @PostMapping("/mint")
    public Object mint(@RequestBody Map<String, Object> request,
                       @RequestParam(value = "fromPubkey", required = false) String fromPubkey,
                       @RequestParam(value = "prikey", required = false) String prikey,
                       @RequestParam(value = "nonce", required = false, defaultValue = "0") long nonce) {
        // 优先使用查询参数中的公钥/私钥；否则从请求体取 from 字段作为公钥
        String pubkey = fromPubkey != null ? fromPubkey : (String) request.get("from");
        Object collateralObj = request.get("collateralAmount");
        Object mintObj = request.get("mintAmount");

        long collateralAmount = collateralObj != null ? Long.parseLong(collateralObj.toString()) : 0L;
        long mintAmount = mintObj != null ? Long.parseLong(mintObj.toString()) : 0L;

        // 本地抵押率校验（与 Service 中仓位级别校验互补）
        if (collateralAmount > 0 && mintAmount > 0) {
            double ratio = (double) collateralAmount / (double) mintAmount;
            if (ratio < collateralRatio) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "collateral ratio " + ratio + " is below minimum " + collateralRatio);
            }
        }

        return stableCoinService.mint(pubkey, collateralAmount, mintAmount, prikey, nonce);
    }

    /**
     * 赎回稳定币。
     *
     * <p>销毁稳定币并取回对应的抵押 NEX 代币。赎回金额须不超过已铸造金额。</p>
     *
     * @param request 请求体，包含以下字段：
     *                <ul>
     *                  <li>{@code from} - 赎回人地址（或公钥十六进制）</li>
     *                  <li>{@code redeemAmount} - 赎回稳定币数量（最小单位）</li>
     *                </ul>
     * @param fromPubkey 赎回人公钥（十六进制字符串，32 字节），可选
     * @param prikey 赎回人私钥（十六进制字符串，32 字节），可选
     * @param nonce 交易 nonce
     * @return 统一响应格式，data 中包含赎回结果
     */
    @PostMapping("/redeem")
    public Object redeem(@RequestBody Map<String, Object> request,
                         @RequestParam(value = "fromPubkey", required = false) String fromPubkey,
                         @RequestParam(value = "prikey", required = false) String prikey,
                         @RequestParam(value = "nonce", required = false, defaultValue = "0") long nonce) {
        String pubkey = fromPubkey != null ? fromPubkey : (String) request.get("from");
        Object redeemObj = request.get("redeemAmount");

        long redeemAmount = redeemObj != null ? Long.parseLong(redeemObj.toString()) : 0L;

        return stableCoinService.redeem(pubkey, redeemAmount, prikey, nonce);
    }

    /**
     * 查询地址的抵押率和仓位信息。
     *
     * @param address NEX 地址（URL 路径参数）
     * @return 统一响应格式，data 中包含 {@link StableCoinPosition} 对象
     */
    @GetMapping("/collateral/{address}")
    public Object getCollateral(@PathVariable("address") String address) {
        return stableCoinService.getCollateralRatio(address);
    }

    /**
     * 查询稳定币当前价格。
     *
     * @return 统一响应格式，data 中包含价格信息
     */
    @GetMapping("/price")
    public Object getPrice() {
        return stableCoinService.getPrice();
    }
}
