package org.nexus.settlement.clearing;

import jakarta.annotation.PostConstruct;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 结算平台出款地址的启动期守卫。
 *
 * <p>语义（板 20261105-*）：结算开启时，平台出款地址缺失、仍是历史占位符、或当前
 * {@link OnChainExecutionChannel} 无法把它解析成可用链上地址，<b>应用上下文直接启动失败</b>，
 * 不再允许「启动成功、每笔结算才失败」。校验发生在 {@code @PostConstruct}——即
 * {@code ScheduledAnnotationBeanPostProcessor} 注册 {@code @Scheduled} 任务之前，
 * 因此调度器不可能先跑一轮脏数据。</p>
 *
 * <p>唯一的合法绕过是部署方显式设置 {@code nexus.settlement.enabled=false}
 * （环境变量 {@code NEX_SETTLEMENT_ENABLED=false}）。此时地址校验跳过，同时
 * {@code SettlementScheduler} 不执行、{@link DefaultClearingEngine} 的结算入口
 * 直接落 FAILED（未启用），不会「跳过校验但功能照跑」。</p>
 *
 * <p>注意：{@code @Value("${nexus.settlement.platform-address:}")} 里的 {@code :} 只把
 * 「未设置」映射为空串，让禁用模式可以无地址启动；它<b>不提供任何地址回退值</b>，
 * 非禁用模式下空串会在这里直接抛错。</p>
 */
@Component
public class SettlementPlatformAddressGuard {

    private static final Logger log = LoggerFactory.getLogger(SettlementPlatformAddressGuard.class);

    static final String ADDRESS_KEY = "nexus.settlement.platform-address";
    static final String ADDRESS_ENV = "NEX_SETTLEMENT_PLATFORM_ADDRESS";
    static final String ENABLED_KEY = "nexus.settlement.enabled";
    static final String ENABLED_ENV = "NEX_SETTLEMENT_ENABLED";

    /**
     * 历史占位符 / 部署模板占位符：即使当前执行通道的 {@code canTransferTo} 实现过于宽松
     * （例如 sandbox 只做非空校验），也不能让它们通过启动校验。
     */
    static final Set<String> PLACEHOLDER_ADDRESSES = Set.of(
            "PLATFORM_HOT_WALLET",
            "SANDBOX_PLATFORM_SETTLEMENT_WALLET",
            "CHANGE_ME_PLATFORM_SETTLEMENT_ADDRESS",
            "REPLACE_ME_PLATFORM_SETTLEMENT_ADDRESS");

    private final OnChainExecutionChannel executionChannel;

    @Value("${" + ENABLED_KEY + ":true}")
    private boolean settlementEnabled;

    @Value("${" + ADDRESS_KEY + ":}")
    private String platformAddress;

    public SettlementPlatformAddressGuard(OnChainExecutionChannel executionChannel) {
        this.executionChannel = executionChannel;
    }

    @PostConstruct
    public void validate() {
        if (!settlementEnabled) {
            log.warn("settlement is explicitly disabled ({}={} / {}={}); platform address validation skipped",
                    ENABLED_KEY, false, ENABLED_ENV, false);
            return;
        }

        String address = platformAddress == null ? "" : platformAddress.trim();
        if (address.isEmpty()) {
            throw new IllegalStateException(
                    "结算已启用，但平台出款地址未配置：请设置配置项 " + ADDRESS_KEY
                            + "（环境变量 " + ADDRESS_ENV + "）为平台热钱包的链上地址；"
                            + "若本环境确实不做结算，请显式设置 " + ENABLED_KEY
                            + "=false（环境变量 " + ENABLED_ENV + "=false）后重启。");
        }

        String normalized = address.toUpperCase(Locale.ROOT);
        if (PLACEHOLDER_ADDRESSES.contains(normalized)) {
            throw new IllegalStateException(
                    "结算已启用，但平台出款地址仍是占位符 [" + address + "]：请把 " + ADDRESS_KEY
                            + "（环境变量 " + ADDRESS_ENV + "）替换为真实平台热钱包链上地址；"
                            + "若本环境确实不做结算，请显式设置 " + ENABLED_KEY
                            + "=false（环境变量 " + ENABLED_ENV + "=false）后重启。");
        }

        if (!executionChannel.canTransferTo(address)) {
            throw new IllegalStateException(
                    "结算已启用，但平台出款地址无法被当前 OnChainExecutionChannel 解析为可用链上地址："
                            + ADDRESS_KEY + "=" + address + "（canTransferTo=false）。"
                            + "请提供合法的平台热钱包地址，或显式设置 " + ENABLED_KEY
                            + "=false（环境变量 " + ENABLED_ENV + "=false）后重启。");
        }

        log.info("settlement platform address validated: {}={}", ADDRESS_KEY, address);
    }
}
