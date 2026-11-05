package org.nexus.settlement.clearing;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结算平台出款地址的启动期 fail-fast 契约。
 *
 * <p>验收语义：结算开启时，地址缺失 / 占位符 / 不可解析都必须让 ApplicationContext
 * 启动失败，且失败原因点名配置键；唯一合法绕过是显式
 * {@code nexus.settlement.enabled=false}，此时能启动且结算入口返回明确的「未启用」失败。</p>
 */
class SettlementPlatformAddressGuardTest {

    private static final String VALID_ADDRESS = "1TestPlatformWalletAddress000000000000000";
    private static final String INVALID_ADDRESS = "not-a-chain-address";

    private static OnChainExecutionChannel channel(boolean parsable) {
        return new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                throw new UnsupportedOperationException("not used in guard tests");
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                throw new UnsupportedOperationException("not used in guard tests");
            }

            @Override
            public boolean canTransferTo(String toAddress) {
                return parsable && VALID_ADDRESS.equals(toAddress);
            }
        };
    }

    private static ApplicationContextRunner runner(OnChainExecutionChannel channel) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SettlementPlatformAddressGuard.class)
                .withBean(OnChainExecutionChannel.class, () -> channel);
    }

    private static Throwable deepest(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    void startupFailsWhenAddressIsMissing() {
        runner(channel(true)).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = deepest(context.getStartupFailure());
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage())
                    .contains("nexus.settlement.platform-address")
                    .contains("NEX_SETTLEMENT_PLATFORM_ADDRESS")
                    .contains("nexus.settlement.enabled=false");
        });
    }

    @Test
    void startupFailsWhenAddressIsHistoricalPlaceholder() {
        runner(channel(true))
                .withPropertyValues("nexus.settlement.platform-address=PLATFORM_HOT_WALLET")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = deepest(context.getStartupFailure());
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage())
                            .contains("nexus.settlement.platform-address")
                            .contains("PLATFORM_HOT_WALLET")
                            .contains("NEX_SETTLEMENT_PLATFORM_ADDRESS");
                });
    }

    @Test
    void startupFailsWhenAddressCannotBeParsedByExecutionChannel() {
        runner(channel(false))
                .withPropertyValues("nexus.settlement.platform-address=" + INVALID_ADDRESS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = deepest(context.getStartupFailure());
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage())
                            .contains("nexus.settlement.platform-address")
                            .contains(INVALID_ADDRESS)
                            .contains("canTransferTo=false");
                });
    }

    @Test
    void startupSucceedsWhenSettlementIsExplicitlyDisabledAndEntryPointReturnsNotEnabled() {
        runner(channel(false))
                .withPropertyValues("nexus.settlement.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SettlementPlatformAddressGuard.class);

                    // 不是「bean 存在」就完事：结算入口必须返回明确的未启用失败语义，
                    // 且不写 ledger、不动链。
                    DefaultClearingEngine engine = new DefaultClearingEngine(
                            null, null, channel(false), "", false);

                    ClearingOrder order = new ClearingOrder();
                    order.setOrderId("order-disabled-1");
                    order.setMerchantId("merchant-disabled-1");
                    order.setAmount(BigDecimal.TEN);
                    ClearingOrder settled = engine.settle(order);
                    assertThat(settled.getStatus()).isEqualTo(ClearingOrder.OrderStatus.FAILED);
                    assertThat(settled.getSettlementTxHash()).isNull();

                    ClearingOrder another = new ClearingOrder();
                    another.setOrderId("order-disabled-2");
                    another.setMerchantId("merchant-disabled-2");
                    another.setAmount(BigDecimal.ONE);
                    SettlementBatch batch = new SettlementBatch();
                    batch.setBatchNo("batch-disabled");
                    batch.setOrders(List.of(another));
                    assertThat(engine.batchClear(batch).getStatus())
                            .isEqualTo(SettlementBatch.BatchStatus.FAILED);
                });
    }

    @Test
    void startupSucceedsWhenValidAddressIsConfigured() {
        runner(channel(true))
                .withPropertyValues("nexus.settlement.platform-address=" + VALID_ADDRESS)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * 反回退门禁：把历史实现（占位地址常量 / 两参构造器 / 带非空默认值的 {@code @Value}）
     * 改回来，这条用例必须红。它不参与运行时逻辑，只锁住「不允许隐式回退 + 校验必须
     * 在 @PostConstruct」两个结构事实。
     */
    @Test
    void legacyImplicitFallbacksAndLateValidationMustNotComeBack() throws Exception {
        assertThat(DefaultClearingEngine.class.getDeclaredFields())
                .noneMatch(f -> f.getName().contains("SANDBOX_PLATFORM_SETTLEMENT"));
        assertThat(DefaultClearingEngine.class.getDeclaredConstructors())
                .noneMatch(c -> c.getParameterCount() == 2);

        Field addressField = SettlementPlatformAddressGuard.class.getDeclaredField("platformAddress");
        Value addressValue = addressField.getAnnotation(Value.class);
        assertThat(addressValue).isNotNull();
        assertThat(addressValue.value()).isEqualTo("${nexus.settlement.platform-address:}");

        java.lang.reflect.Constructor<?> springConstructor =
                java.util.Arrays.stream(DefaultClearingEngine.class.getDeclaredConstructors())
                        .filter(c -> c.getParameterCount() == 5)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("DefaultClearingEngine 缺少 Spring 五参构造器"));
        assertThat(springConstructor.getParameters()[3].getAnnotation(Value.class).value())
                .isEqualTo("${nexus.settlement.platform-address:}");

        Method validate = SettlementPlatformAddressGuard.class.getDeclaredMethod("validate");
        assertThat(validate.isAnnotationPresent(PostConstruct.class))
                .as("启动校验必须仍在 @PostConstruct（早于调度器注册）")
                .isTrue();
    }
}
