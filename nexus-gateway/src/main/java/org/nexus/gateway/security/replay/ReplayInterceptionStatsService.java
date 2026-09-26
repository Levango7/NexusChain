package org.nexus.gateway.security.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 防重放拦截统计服务。
 *
 * <p>提供拦截事件记录和统计查询能力。每次防重放拦截（时间戳过期、nonce 重放、
 * nonce 过短、幂等键无效等）调用 {@link #recordInterception} 记录一条统计，
 * 运营人员可通过 {@link #getStats} 查询指定时间范围内的拦截统计。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.2、§4.2.2。</p>
 */
@Service
public class ReplayInterceptionStatsService {

    private static final Logger log = LoggerFactory.getLogger(ReplayInterceptionStatsService.class);

    private final ReplayInterceptionStatsRepository repository;

    public ReplayInterceptionStatsService(ReplayInterceptionStatsRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次防重放拦截。
     *
     * @param tenantId   租户 ID
     * @param merchantId 商户 ID（可为 null）
     * @param errorCode  错误码（如 40103/40106/40109/40110/40111/40112）
     */
    @Transactional
    public void recordInterception(String tenantId, Long merchantId, String errorCode) {
        ReplayInterceptionStat stat = new ReplayInterceptionStat();
        stat.setTenantId(tenantId);
        stat.setMerchantId(merchantId);
        stat.setErrorCode(errorCode);
        stat.setInterceptedAt(LocalDateTime.now());
        repository.save(stat);
        log.info("Replay interception recorded: tenant={}, merchant={}, errorCode={}",
                tenantId, merchantId, errorCode);
    }

    /**
     * 查询指定时间范围内的拦截统计。
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 统计结果，包含总拦截数、按错误码分组、按商户分组
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(String tenantId, LocalDateTime startTime, LocalDateTime endTime) {
        List<ReplayInterceptionStat> stats = repository.findByTenantIdAndTimeRange(tenantId, startTime, endTime);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalInterceptions", stats.size());

        // 按错误码分组统计
        Map<String, Long> byErrorCode = new LinkedHashMap<>();
        for (ReplayInterceptionStat stat : stats) {
            byErrorCode.merge(stat.getErrorCode(), 1L, Long::sum);
        }
        result.put("byErrorCode", byErrorCode);

        // 按商户分组统计
        Map<Long, Long> byMerchant = new LinkedHashMap<>();
        for (ReplayInterceptionStat stat : stats) {
            if (stat.getMerchantId() != null) {
                byMerchant.merge(stat.getMerchantId(), 1L, Long::sum);
            }
        }
        result.put("byMerchant", byMerchant);

        return result;
    }
}