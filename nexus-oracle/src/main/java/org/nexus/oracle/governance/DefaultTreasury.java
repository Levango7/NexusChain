package org.nexus.oracle.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link Treasury} 默认骨架实现。
 *
 * <p>当前为占位实现，balance 返回 0，spend 一律返回 false。
 * 后续接入链上国库合约 + 提案校验后填充业务逻辑。
 */
@Slf4j
@Service
public class DefaultTreasury implements Treasury {

    @Override
    public BigDecimal balance() {
        // TODO: 查询链上国库合约余额
        log.debug("balance skeleton invoked");
        return BigDecimal.ZERO;
    }

    @Override
    public boolean spend(BigDecimal amount, String to, String proposalId) {
        // TODO: 校验提案状态为 PASSED + 类型为 TREASURY_SPEND → 调用链上转账 → 记录历史
        log.debug("spend skeleton invoked: amount={}, to={}, proposalId={}", amount, to, proposalId);
        return false;
    }

    @Override
    public List<Map<String, Object>> getHistory() {
        // TODO: 查询国库支出历史表
        log.debug("getHistory skeleton invoked");
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getSpend(String spendId) {
        // TODO: 查询单笔支出详情
        log.debug("getSpend skeleton invoked: spendId={}", spendId);
        return null;
    }
}