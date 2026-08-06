package org.nexus.governance.delegation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投票权委托服务（liquid democracy 单层委托）。
 *
 * <p>委托人（delegator）可把自身投票权委托给受托人（delegatee），
 * 受托人在计票时累计获得所有委托人的质押量作为额外权重。
 * 任一时刻一个委托人只能委托给一个受托人；取消委托后权重回归自身。</p>
 *
 * <h3>单层委托</h3>
 * <p>当前实现不支持传递委托（A→B→C），仅支持单层（A→B）。
 * 若需传递委托可在受托人查询时递归解析，但需防范委托环。</p>
 *
 * <h3>自委托禁止</h3>
 * <p>委托给自身无意义且会形成环，被拒绝。</p>
 *
 * @since 1.4
 */
@Component
public class DelegationService {

    private static final Logger logger = LoggerFactory.getLogger(DelegationService.class);

    /** delegator -> delegatee */
    private final ConcurrentHashMap<String, String> delegation = new ConcurrentHashMap<>();

    /** delegatee -> delegators（反向索引，便于受托人累计权重） */
    private final ConcurrentHashMap<String, Set<String>> reverseIndex = new ConcurrentHashMap<>();

    /**
     * 建立委托关系。
     *
     * @param delegator 委托人地址
     * @param delegatee 受托人地址
     * @return 委托成功返回 true；自委托、参数非法或重复委托给同一人返回 false
     */
    public boolean delegate(String delegator, String delegatee) {
        if (delegator == null || delegatee == null) {
            logger.warn("Delegate rejected: null parameter");
            return false;
        }
        if (delegator.equals(delegatee)) {
            logger.warn("Delegate rejected: delegator {} cannot delegate to self", delegator);
            return false;
        }
        String existing = delegation.putIfAbsent(delegator, delegatee);
        if (existing != null) {
            if (existing.equals(delegatee)) {
                logger.warn("Delegate rejected: delegator {} already delegates to {}", delegator, delegatee);
                return false;
            }
            // 已委托给其他人，先解除旧委托再建立新委托
            undelegate(delegator);
            delegation.put(delegator, delegatee);
        }
        reverseIndex.computeIfAbsent(delegatee, k -> ConcurrentHashMap.newKeySet()).add(delegator);
        logger.info("Delegation: {} -> {}", delegator, delegatee);
        return true;
    }

    /**
     * 取消委托关系。
     *
     * @param delegator 委托人地址
     * @return 取消成功返回 true；未委托返回 false
     */
    public boolean undelegate(String delegator) {
        if (delegator == null) {
            return false;
        }
        String delegatee = delegation.remove(delegator);
        if (delegatee == null) {
            return false;
        }
        Set<String> delegators = reverseIndex.get(delegatee);
        if (delegators != null) {
            delegators.remove(delegator);
            if (delegators.isEmpty()) {
                reverseIndex.remove(delegatee);
            }
        }
        logger.info("Undelegation: {} (was -> {})", delegator, delegatee);
        return true;
    }

    /**
     * 查询委托人的受托人。
     *
     * @param delegator 委托人地址
     * @return 受托人地址；未委托返回 {@link Optional#empty()}
     */
    public Optional<String> getDelegatee(String delegator) {
        if (delegator == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(delegation.get(delegator));
    }

    /**
     * 查询委托给指定受托人的所有委托人。
     *
     * @param delegatee 受托人地址
     * @return 委托人集合（只读）；无人委托返回空集合
     */
    public Set<String> getDelegators(String delegatee) {
        if (delegatee == null) {
            return Collections.emptySet();
        }
        Set<String> delegators = reverseIndex.get(delegatee);
        if (delegators == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(delegators);
    }

    /**
     * 判断指定地址是否为受托人（有人委托给他）。
     *
     * @param delegatee 受托人地址
     * @return 是受托人返回 true
     */
    public boolean isDelegatee(String delegatee) {
        return delegatee != null && reverseIndex.containsKey(delegatee) && !reverseIndex.get(delegatee).isEmpty();
    }
}