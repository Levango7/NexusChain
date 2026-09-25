package org.nexus.gateway.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 乐观锁重试模板 — 在独立事务中执行操作，乐观锁冲突时自动重试。
 *
 * <p>核心机制：每次重试使用 {@link TransactionTemplate#execute} 开启<strong>新事务</strong>，
 * 避免在已标记 rollback-only 的旧事务中重试导致永远失败的问题。
 * （来源经验：2026-09-25-optimistic-lock-retry-transactional-boundary-conflict）</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * OptimisticLockRetryTemplate template = new OptimisticLockRetryTemplate(transactionTemplate, 3);
 * MerchantAccount result = template.execute(() -> {
 *     // 在新事务中执行业务逻辑
 *     return accountRepository.save(account);
 * });
 * }</pre>
 */
public class OptimisticLockRetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetryTemplate.class);

    private final TransactionTemplate transactionTemplate;
    private final int maxRetry;

    /**
     * 构造乐观锁重试模板。
     *
     * @param transactionTemplate 编程式事务管理模板，每次重试开启新事务
     * @param maxRetry            最大重试次数（不含首次执行）
     */
    public OptimisticLockRetryTemplate(TransactionTemplate transactionTemplate, int maxRetry) {
        this.transactionTemplate = transactionTemplate;
        this.maxRetry = maxRetry;
    }

    /**
     * 在独立事务中执行操作，乐观锁冲突时自动重试。
     *
     * <p>当 {@link ObjectOptimisticLockingFailureException} 发生时，当前事务自动回滚，
     * 随后在新事务中重试。重试 {@code maxRetry} 次后仍失败则抛出原始异常。</p>
     *
     * @param action 要执行的操作（在新事务中执行）
     * @return 操作结果
     * @throws ObjectOptimisticLockingFailureException 重试耗尽后仍冲突
     */
    public <T> T execute(Supplier<T> action) {
        int attempts = 0;
        while (true) {
            try {
                return transactionTemplate.execute(status -> action.get());
            } catch (ObjectOptimisticLockingFailureException e) {
                attempts++;
                if (attempts > maxRetry) {
                    log.error("乐观锁冲突，重试 {} 次后仍失败", maxRetry, e);
                    throw e;
                }
                log.warn("乐观锁冲突，正在重试 (attempt={}/{})", attempts, maxRetry);
            }
        }
    }
}