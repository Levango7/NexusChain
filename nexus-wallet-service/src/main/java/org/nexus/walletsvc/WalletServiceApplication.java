package org.nexus.walletsvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 钱包管理服务独立启动入口。
 *
 * <p>Phase 2 微服务化：从 nexus-exchange-wallet 拆分出的钱包管理服务独立 Spring Boot 应用，
 * 承载原 exchange-wallet 的 wallet/ 子包全部端点（提现审批 / 托管 / 白名单 / 链上执行通道）。</p>
 *
 * <p>组件扫描范围：默认扫描 {@code org.nexus.walletsvc} 包及其子包，覆盖：
 * <ul>
 *   <li>{@code approval}：WithdrawalApprovalService / DefaultWithdrawalApprovalService /
 *       DefaultApprovalPolicy（提现审批流程）</li>
 *   <li>{@code custody}：CustodyService / DefaultCustodyService / CustodyPolicy（托管策略）</li>
 *   <li>{@code whitelist}：AddressWhitelistService / DefaultAddressWhitelistService /
 *       WhitelistEntry（地址白名单）</li>
 *   <li>{@code execution}：OnChainExecutionClient / HttpOnChainExecutionClient（链上执行通道）</li>
 *   <li>{@code controller}：WalletController（REST 端点）</li>
 * </ul></p>
 *
 * <p>SCA 集成：
 * <ul>
 *   <li>Nacos discovery + config：bootstrap.yml 配置，自动注册到 Nacos</li>
 *   <li>Sentinel：application.yml 配置 transport-dashboard，熔断限流</li>
 *   <li>OpenFeign：@EnableFeignClients 启用，通过 SigningServiceFeignClient
 *       调用 signing-service 完成提现签名广播（设计文档 §3.2 方案 A）</li>
 * </ul></p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = {"org.nexus.sdk.client.feign"})
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
