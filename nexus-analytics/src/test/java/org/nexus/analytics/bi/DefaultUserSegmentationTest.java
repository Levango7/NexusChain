package org.nexus.analytics.bi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.analytics.onchain.InMemoryTransactionDataSource;
import org.nexus.analytics.onchain.OnChainTransaction;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultUserSegmentation} 补充测试。
 *
 * <p>覆盖 LONG_TAIL 分群、getSegmentProfile 画像计算与 null/blank 边界。
 */
class DefaultUserSegmentationTest {

    private InMemoryTransactionDataSource dataSource;
    private DefaultUserSegmentation segmentation;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
        segmentation = new DefaultUserSegmentation(dataSource);
    }

    private OnChainTransaction tx(String from, String to, long amount) {
        return OnChainTransaction.builder()
                .txHash("h-" + from + to + amount + System.nanoTime())
                .fromAddress(from).toAddress(to)
                .amount(BigInteger.valueOf(amount))
                .timestamp(Instant.now())
                .status(OnChainTransaction.Status.SUCCESS)
                .build();
    }

    @Test
    void segment_nullOrBlank_shouldReturnDormant() {
        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, segmentation.segment(null));
        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, segmentation.segment(""));
        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, segmentation.segment("   "));
    }

    @Test
    void segment_noTransactions_shouldReturnDormant() {
        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, segmentation.segment("UNKNOWN"));
    }

    @Test
    void segment_longTail_atLeast5Txs_shouldReturnLongTail() {
        // 5 笔交易，金额小，收款占比低
        dataSource.feed(List.of(
                tx("A", "B", 100),
                tx("A", "C", 100),
                tx("A", "D", 100),
                tx("A", "E", 100),
                tx("A", "F", 100)));

        assertEquals(DefaultUserSegmentation.SEGMENT_LONG_TAIL, segmentation.segment("A"));
    }

    @Test
    void segment_merchant_highReceiveRatio_shouldReturnMerchant() {
        // B 作为收款方 3/3 = 100%
        dataSource.feed(List.of(
                tx("A", "B", 100),
                tx("C", "B", 100),
                tx("D", "B", 100)));

        assertEquals(DefaultUserSegmentation.SEGMENT_MERCHANT, segmentation.segment("B"));
    }

    @Test
    void segment_highValue_overridesMerchant() {
        // 高净值优先于商户判断
        dataSource.feed(List.of(
                tx("A", "B", 2_000_000),
                tx("C", "B", 2_000_000)));

        assertEquals(DefaultUserSegmentation.SEGMENT_HIGH_VALUE, segmentation.segment("B"));
    }

    @Test
    void getSegmentProfile_nullOrBlank_shouldReturnEmpty() {
        assertTrue(segmentation.getSegmentProfile(null).isEmpty());
        assertTrue(segmentation.getSegmentProfile("").isEmpty());
    }

    @Test
    void getSegmentProfile_dormant_shouldReturnProfile() {
        // 没有交易时，DORMANT 画像 size=0
        Map<String, Object> profile = segmentation.getSegmentProfile(DefaultUserSegmentation.SEGMENT_DORMANT);

        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, profile.get("segmentId"));
        assertEquals(0L, profile.get("size"));
        assertEquals(0.0d, profile.get("avgFrequency"));
        assertEquals(0.0d, profile.get("avgTxAmount"));
    }

    @Test
    void getSegmentProfile_highValue_shouldComputeStats() {
        dataSource.feed(List.of(
                tx("A", "B", 2_000_000),
                tx("A", "C", 500_000)));

        Map<String, Object> profile = segmentation.getSegmentProfile(DefaultUserSegmentation.SEGMENT_HIGH_VALUE);

        assertEquals(DefaultUserSegmentation.SEGMENT_HIGH_VALUE, profile.get("segmentId"));
        // A(2.5M) 与 B(2M) 均为高净值；C(500K) 不是
        assertEquals(2L, profile.get("size"));
        assertTrue((double) profile.get("avgFrequency") > 0);
    }

    @Test
    void getSegmentProfile_merchant_shouldComputeStats() {
        dataSource.feed(List.of(
                tx("A", "M", 100),
                tx("C", "M", 100),
                tx("D", "M", 100)));

        Map<String, Object> profile = segmentation.getSegmentProfile(DefaultUserSegmentation.SEGMENT_MERCHANT);

        assertEquals(DefaultUserSegmentation.SEGMENT_MERCHANT, profile.get("segmentId"));
        assertTrue((long) profile.get("size") >= 1);
    }

    @Test
    void listSegments_shouldReturnFourInOrder() {
        List<String> segments = segmentation.listSegments();

        assertEquals(4, segments.size());
        assertEquals(DefaultUserSegmentation.SEGMENT_HIGH_VALUE, segments.get(0));
        assertEquals(DefaultUserSegmentation.SEGMENT_MERCHANT, segments.get(1));
        assertEquals(DefaultUserSegmentation.SEGMENT_LONG_TAIL, segments.get(2));
        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, segments.get(3));
    }
}