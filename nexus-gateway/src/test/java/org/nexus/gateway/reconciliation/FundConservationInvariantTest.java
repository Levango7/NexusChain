package org.nexus.gateway.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.reconciliation.compensation.AutoCompensationService;
import org.nexus.gateway.reconciliation.compensation.CompensationRecord;
import org.nexus.gateway.reconciliation.compensation.CompensationRecordRepository;
import org.nexus.gateway.reconciliation.rule.ReconciliationRuleConfigRepository;
import org.nexus.gateway.reconciliation.rule.ReconciliationRuleConfigService;
import org.nexus.gateway.reconciliation.rule.StatusMappingService;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 假钱资金守恒不变量测试（2026-10-03，多沙箱闭环）。
 *
 * <p>回答一个问题：<b>渠道侧与内部账在任何随机差错组合下，
 * 每一分钱的差异都能被对账精确量化、且被补偿等额核销吗？</b>
 * 这是对「账证平衡」核心性质的性质测试（固定种子可复现）：随机生成
 * 匹配 / 长款（渠道有内部无）/ 短款（内部有渠道无）/ 金额不一致四类记录组合，
 * 跑对账引擎 + 自动补偿，断言三条守恒律：</p>
 *
 * <ol>
 *   <li><b>分类完备</b>：每条内部记录要么匹配、要么成为差错；渠道侧同理——
 *       matched + missing == 内部总数，matched + extra == 渠道总数；</li>
 *   <li><b>金额守恒</b>：Σ(渠道金额) − Σ(内部金额) ==
 *       Σ(每条差错的 channelAmount − internalAmount)——
 *       差异总额不多不少正好被差错表量化；</li>
 *   <li><b>补偿核销平衡</b>：对每条金额类差错（LONG/SHORT/MISMATCH）：
 *       恰产生一条补偿，且<b>补偿额 == 该差错差异绝对额</b>（逐条核销完备）；
 *       聚合上 Σ 补偿额 == Σ 差异绝对额——每分差异都有等额补偿核销。
 *       非金额类差错（状态/信息不一致）不产生补偿也不破坏守恒（贡献额为 0）。</li>
 * </ol>
 *
 * <p>这是沙箱假钱能做的最接近真实资金安全的验证：不验证「钱对不对」，
 * 验证「算账系统本身不会丢钱」。</p>
 */
class FundConservationInvariantTest {

    private static final Long MERCHANT_ID = 500L;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    /** 多个固定种子：随机场景覆盖，结果确定可复现。 */
    private static final long[] SEEDS = {42L, 1337L, 20261003L};

    private ReconciliationEngine engine;
    private AutoCompensationService compensationService;
    private CompensationRecordRepository compensationRepository;
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    /** 单次场景的补偿记录收集器（in-memory 伪仓库 save 直通）。 */
    private List<CompensationRecord> createdCompensations;

    @BeforeEach
    void setUp() throws Exception {
        ReconciliationRuleConfigRepository ruleRepository = mock(ReconciliationRuleConfigRepository.class);
        ReconciliationRuleConfigService ruleConfigService = new ReconciliationRuleConfigService(ruleRepository);
        StatusMappingService statusMappingService = new StatusMappingService(new ObjectMapper());
        engine = new ReconciliationEngine(ruleConfigService, statusMappingService);
        engine.setAmountTolerance(TOLERANCE);

        compensationRepository = mock(CompensationRecordRepository.class);
        discrepancyRepository = mock(ReconciliationDiscrepancyRepository.class);
        compensationService = new AutoCompensationService(compensationRepository, discrepancyRepository);
        // 反射注入 @Value 字段（无 Spring 上下文的单测惯例，同 AutoCompensationServiceTest）
        setField(compensationService, "autoEnabled", true);
        setField(compensationService, "sandbox", true);

        createdCompensations = new ArrayList<>();
        when(compensationRepository.existsByDiscrepancyId(any())).thenReturn(false);
        when(compensationRepository.save(any())).thenAnswer(inv -> {
            CompensationRecord record = inv.getArgument(0);
            record.setId((long) (createdCompensations.size() + 1));
            createdCompensations.add(record);
            return record;
        });
    }

    // ==================== 主不变量：三种子随机场景 ====================

    @Test
    @DisplayName("守恒律: 随机差错组合下 分类完备 + 金额守恒 + 补偿核销平衡")
    void conservationHoldsAcrossRandomScenarios() throws Exception {
        for (long seed : SEEDS) {
            Scenario scenario = generate(seed);
            ReconciliationDiffReport report = engine.reconcile(
                    MERCHANT_ID, null, scenario.channelRecords, scenario.internalOrders, null);

            assertClassificationComplete(seed, report, scenario);
            assertAmountConserved(seed, report, scenario);
            assertCompensationBalances(seed, report);
        }
    }

    @Test
    @DisplayName("边界: 全匹配时守恒律退化成立（零差异零补偿）")
    void allMatchedZeroDiscrepancy() {
        List<ChannelRecord> channel = new ArrayList<>();
        List<PaymentOrder> internal = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            BigDecimal amount = amountOf(i, "77.5" + i);
            channel.add(createChannelRecord("ORD-" + i, amount, "PAID"));
            internal.add(createPaymentOrder("ORD-" + i, amount, PaymentOrder.OrderStatus.PAID));
        }
        ReconciliationDiffReport report = engine.reconcile(MERCHANT_ID, null, channel, internal, null);

        assertEquals(0, report.getDiscrepancies().size());
        assertAmountConserved(0L, report, new Scenario(channel, internal, 0, 0, 0));
    }

    // ==================== 三条守恒律的断言 ====================

    private void assertClassificationComplete(long seed, ReconciliationDiffReport report, Scenario scenario) {
        assertEquals(scenario.internalOrders.size(), report.getTotalInternal(),
                "种子 " + seed + "：内部总数不符");
        assertEquals(scenario.channelRecords.size(), report.getTotalChannel(),
                "种子 " + seed + "：渠道总数不符");
        // 分类完备：每条内部记录要么匹配、要么短款（渠道无）、要么配对但金额不一致
        //（MISMATCH 双侧都计入——两侧各有一条记录）；渠道侧同理
        assertEquals(report.getMatchedCount()
                        + countByType(report, ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT)
                        + countByType(report, ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH),
                report.getTotalInternal(), "种子 " + seed + "：内部记录分类不完备");
        assertEquals(report.getMatchedCount()
                        + countByType(report, ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT)
                        + countByType(report, ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH),
                report.getTotalChannel(), "种子 " + seed + "：渠道记录分类不完备");
    }

    private void assertAmountConserved(long seed, ReconciliationDiffReport report, Scenario scenario) {
        BigDecimal channelSum = sumChannel(scenario.channelRecords);
        BigDecimal internalSum = sumInternal(scenario.internalOrders);
        BigDecimal discrepancySum = BigDecimal.ZERO;
        for (ReconciliationDiscrepancy d : report.getDiscrepancies()) {
            BigDecimal channelSide = d.getChannelAmount() != null ? d.getChannelAmount() : BigDecimal.ZERO;
            BigDecimal internalSide = d.getInternalAmount() != null ? d.getInternalAmount() : BigDecimal.ZERO;
            discrepancySum = discrepancySum.add(channelSide).subtract(internalSide);
        }
        assertEquals(0, channelSum.subtract(internalSum).compareTo(discrepancySum),
                "种子 " + seed + "：金额守恒被破坏——渠道/内部差 "
                        + channelSum.subtract(internalSum) + " ≠ 差错量化 " + discrepancySum);
    }

    private void assertCompensationBalances(long seed, ReconciliationDiffReport report) throws Exception {
        createdCompensations.clear();
        BigDecimal expectedMagnitudeTotal = BigDecimal.ZERO;
        for (ReconciliationDiscrepancy d : report.getDiscrepancies()) {
            BigDecimal channelSide = d.getChannelAmount() != null ? d.getChannelAmount() : BigDecimal.ZERO;
            BigDecimal internalSide = d.getInternalAmount() != null ? d.getInternalAmount() : BigDecimal.ZERO;
            BigDecimal magnitude = channelSide.subtract(internalSide).abs();
            switch (d.getDiscrepancyType()) {
                case LONG_AMOUNT, SHORT_AMOUNT, AMOUNT_MISMATCH -> {
                    expectedMagnitudeTotal = expectedMagnitudeTotal.add(magnitude);
                    CompensationRecord rec = compensationService
                            .createCompensationForDiscrepancy(d)
                            .orElseThrow(() -> new AssertionError(
                                    "种子 " + seed + "：金额类差错未产生补偿 " + d.getTransactionId()));
                    // 核销完备（逐条）：每条差错的差异绝对额都有等额补偿
                    assertEquals(0, magnitude.compareTo(rec.getAmount()),
                            "种子 " + seed + "：补偿额 ≠ 差异额 " + d.getTransactionId()
                                    + "（应 " + magnitude + " 实 " + rec.getAmount() + "）");
                }
                default ->
                    // 状态/信息类差错：金额贡献为 0，不应触发资金补偿
                    assertTrue(compensationService.createCompensationForDiscrepancy(d).isEmpty(),
                            "种子 " + seed + "：非金额差错不应产生补偿 " + d.getTransactionId());
            }
        }
        // 账证平衡（聚合）：全部补偿金额之和 == 全部差异绝对额之和
        BigDecimal compensationSum = BigDecimal.ZERO;
        for (CompensationRecord c : createdCompensations) {
            compensationSum = compensationSum.add(c.getAmount());
        }
        assertEquals(0, expectedMagnitudeTotal.compareTo(compensationSum),
                "种子 " + seed + "：补偿核销不平衡——应补 " + expectedMagnitudeTotal + " 实补 " + compensationSum);
    }

    // ==================== 随机场景生成 ====================

    private record Scenario(List<ChannelRecord> channelRecords,
                            List<PaymentOrder> internalOrders,
                            int longCount, int shortCount, int mismatchCount) {
    }

    private Scenario generate(long seed) {
        Random random = new Random(seed);
        int matched = 5 + random.nextInt(20);
        int longs = random.nextInt(8);
        int shorts = random.nextInt(8);
        int mismatches = random.nextInt(8);

        List<ChannelRecord> channel = new ArrayList<>();
        List<PaymentOrder> internal = new ArrayList<>();
        int seq = 0;

        for (int i = 0; i < matched; i++) {
            BigDecimal amount = randomAmount(random);
            channel.add(createChannelRecord("ORD-" + seq, amount, "PAID"));
            internal.add(createPaymentOrder("ORD-" + seq, amount, PaymentOrder.OrderStatus.PAID));
            seq++;
        }
        for (int i = 0; i < longs; i++) {
            channel.add(createChannelRecord("ORD-" + seq, randomAmount(random), "PAID"));
            seq++;
        }
        for (int i = 0; i < shorts; i++) {
            internal.add(createPaymentOrder("ORD-" + seq, randomAmount(random), PaymentOrder.OrderStatus.PAID));
            seq++;
        }
        for (int i = 0; i < mismatches; i++) {
            BigDecimal internalAmount = randomAmount(random);
            // 差额恒 > 容差（0.5~99.50），确保被归类为 AMOUNT_MISMATCH 而非容差内匹配
            BigDecimal delta = new BigDecimal("0.5").add(
                    BigDecimal.valueOf(random.nextInt(9900), 2));
            BigDecimal channelAmount = internalAmount.add(delta);
            channel.add(createChannelRecord("ORD-" + seq, channelAmount, "PAID"));
            internal.add(createPaymentOrder("ORD-" + seq, internalAmount, PaymentOrder.OrderStatus.PAID));
            seq++;
        }
        return new Scenario(channel, internal, longs, shorts, mismatches);
    }

    private BigDecimal randomAmount(Random random) {
        return BigDecimal.valueOf(10 + random.nextInt(1_000_00), 2);
    }

    private BigDecimal amountOf(int i, String prefix) {
        return new BigDecimal(prefix).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumChannel(List<ChannelRecord> records) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ChannelRecord r : records) {
            sum = sum.add(r.getAmount());
        }
        return sum;
    }

    private BigDecimal sumInternal(List<PaymentOrder> orders) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PaymentOrder o : orders) {
            sum = sum.add(o.getAmount());
        }
        return sum;
    }

    private long countByType(ReconciliationDiffReport report,
                              ReconciliationDiscrepancy.DiscrepancyType type) {
        return report.getDiscrepancies().stream()
                .filter(d -> d.getDiscrepancyType() == type)
                .count();
    }

    private ChannelRecord createChannelRecord(String transactionId, BigDecimal amount, String status) {
        ChannelRecord record = new ChannelRecord();
        record.setTransactionId(transactionId);
        record.setAmount(amount);
        record.setStatus(status);
        record.setCurrency("NEX");
        return record;
    }

    private PaymentOrder createPaymentOrder(String orderNo, BigDecimal amount,
                                             PaymentOrder.OrderStatus status) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(orderNo);
        order.setMerchantId(MERCHANT_ID);
        order.setAmount(amount);
        order.setStatus(status);
        order.setTokenSymbol("NEX");
        order.setCreatedAt(LocalDateTime.of(2026, 10, 3, 10, 0));
        return order;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
