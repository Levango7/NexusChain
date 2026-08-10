package org.nexus.analytics.bi;

import lombok.extern.slf4j.Slf4j;
import org.nexus.analytics.onchain.OnChainTransaction;
import org.nexus.analytics.onchain.TransactionDataSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link UserSegmentation} 默认实现。
 *
 * <p>基于用户（地址）的交易行为特征归入预定义分群：
 * <ul>
 *   <li><b>HIGH_VALUE</b>（高净值）：累计交易额 ≥ 100 万</li>
 *   <li><b>MERCHANT</b>（商户）：作为收款方的交易占比 ≥ 60%</li>
 *   <li><b>LONG_TAIL</b>（长尾活跃）：交易笔数 ≥ 5 且未达高净值</li>
 *   <li><b>DORMANT</b>（沉默）：无交易或笔数 &lt; 5 且不满足上述条件</li>
 * </ul>
 * 当前为进程内即时计算，基于 {@link TransactionDataSource}。
 */
@Slf4j
@Service
public class DefaultUserSegmentation implements UserSegmentation {

    /** 高净值分群 ID */
    public static final String SEGMENT_HIGH_VALUE = "HIGH_VALUE";
    /** 商户分群 ID */
    public static final String SEGMENT_MERCHANT = "MERCHANT";
    /** 长尾活跃分群 ID */
    public static final String SEGMENT_LONG_TAIL = "LONG_TAIL";
    /** 沉默分群 ID */
    public static final String SEGMENT_DORMANT = "DORMANT";

    /** 高净值阈值（最小计量单位，100 万） */
    private static final BigInteger HIGH_VALUE_THRESHOLD = BigInteger.valueOf(1_000_000);
    /** 长尾活跃笔数阈值 */
    private static final int LONG_TAIL_TX_THRESHOLD = 5;
    /** 商户收款占比阈值 */
    private static final double MERCHANT_RECEIVE_RATIO = 0.6d;

    private final TransactionDataSource dataSource;

    public DefaultUserSegmentation(TransactionDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String segment(String userId) {
        if (userId == null || userId.isBlank()) {
            return SEGMENT_DORMANT;
        }
        List<OnChainTransaction> txs = dataSource.fetchByAddress(userId);
        if (txs.isEmpty()) {
            return SEGMENT_DORMANT;
        }

        BigInteger totalVolume = txs.stream()
                .map(OnChainTransaction::getAmount)
                .filter(a -> a != null)
                .reduce(BigInteger.ZERO, BigInteger::add);
        long receivedCount = txs.stream()
                .filter(tx -> userId.equals(tx.getToAddress()))
                .count();

        if (totalVolume.compareTo(HIGH_VALUE_THRESHOLD) >= 0) {
            return SEGMENT_HIGH_VALUE;
        }
        if (!txs.isEmpty() && (double) receivedCount / txs.size() >= MERCHANT_RECEIVE_RATIO) {
            return SEGMENT_MERCHANT;
        }
        if (txs.size() >= LONG_TAIL_TX_THRESHOLD) {
            return SEGMENT_LONG_TAIL;
        }
        return SEGMENT_DORMANT;
    }

    @Override
    public Map<String, Object> getSegmentProfile(String segmentId) {
        Map<String, Object> profile = new LinkedHashMap<>();
        if (segmentId == null || segmentId.isBlank()) {
            return profile;
        }
        long size = 0;
        BigInteger totalVolume = BigInteger.ZERO;
        long totalTxCount = 0;

        for (String address : distinctAddresses()) {
            if (segmentId.equals(segment(address))) {
                size++;
                List<OnChainTransaction> txs = dataSource.fetchByAddress(address);
                totalTxCount += txs.size();
                totalVolume = totalVolume.add(txs.stream()
                        .map(OnChainTransaction::getAmount)
                        .filter(a -> a != null)
                        .reduce(BigInteger.ZERO, BigInteger::add));
            }
        }
        profile.put("segmentId", segmentId);
        profile.put("size", size);
        profile.put("avgFrequency", size == 0 ? 0.0d
                : BigDecimal.valueOf(totalTxCount).divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP).doubleValue());
        profile.put("avgTxAmount", totalTxCount == 0 ? 0.0d
                : new BigDecimal(totalVolume).divide(BigDecimal.valueOf(totalTxCount), 2, RoundingMode.HALF_UP).doubleValue());
        return profile;
    }

    @Override
    public List<String> listSegments() {
        return List.of(SEGMENT_HIGH_VALUE, SEGMENT_MERCHANT, SEGMENT_LONG_TAIL, SEGMENT_DORMANT);
    }

    private List<String> distinctAddresses() {
        Map<String, Boolean> seen = new HashMap<>();
        for (OnChainTransaction tx : dataSource.fetchAll()) {
            if (tx.getFromAddress() != null) {
                seen.put(tx.getFromAddress(), Boolean.TRUE);
            }
            if (tx.getToAddress() != null) {
                seen.put(tx.getToAddress(), Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }
}
