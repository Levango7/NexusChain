package org.nexus.gateway.subscription.claim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 订阅周期扣款"先认领后动账"的原子认领服务。
 *
 * <p>动账（ChargeExecutor → connector.createPayment）不可逆，因此"谁有权扣这一期"
 * 必须先在数据库里原子裁决。本服务用唯一键表 {@code subscription_charge_claim}
 * 落地：同一订阅同一账期只有一行能落库，落库者即唯一执行方。</p>
 *
 * <p><b>为什么是独立短事务</b>：认领必须在动账之前<b>提交</b>（否则并发的第二个执行方
 * 仍读不到这一行、仍会各扣一次），所以 {@code claimTemplate} 用
 * {@code PROPAGATION_REQUIRES_NEW} 立即提交，与 v1 体系
 * {@code SubscriptionServiceImpl#charge} 的 claimTemplate 同款。撞唯一键的异常在
 * 事务模板之外被捕获并翻译成 {@code false}（模板内部已回滚独立事务），
 * 不会污染调用方所在的外层事务。</p>
 */
@Service
public class SubscriptionChargeClaimService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionChargeClaimService.class);

    private final SubscriptionChargeClaimRepository repository;
    private final TransactionTemplate claimTemplate;

    public SubscriptionChargeClaimService(SubscriptionChargeClaimRepository repository,
                                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.claimTemplate = new TransactionTemplate(transactionManager);
        this.claimTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 原子认领某个订阅的某个账期。
     *
     * @param subscriptionId 订阅业务编号
     * @param cycleKey       账期键（认领时的 nextChargeAt）
     * @return true=本执行方拿到执行权，可以动账；false=该账期已被其他执行方认领，调用方必须放弃
     */
    public boolean tryClaim(String subscriptionId, String cycleKey) {
        if (subscriptionId == null || cycleKey == null) {
            throw new IllegalArgumentException("subscriptionId / cycleKey 不得为空");
        }
        try {
            claimTemplate.executeWithoutResult(status ->
                    repository.saveAndFlush(new SubscriptionChargeClaim(subscriptionId, cycleKey, LocalDateTime.now())));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("订阅扣款账期已被认领（并发重复，本次放弃动账）: subscriptionId={}, cycleKey={}",
                    subscriptionId, cycleKey);
            return false;
        }
    }
}
