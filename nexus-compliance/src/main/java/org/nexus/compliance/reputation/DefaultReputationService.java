package org.nexus.compliance.reputation;

import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 默认信誉评分服务骨架实现。
 * <p>
 * 当前为空实现占位，所有方法体留待后续业务逻辑填充。
 * </p>
 */
@Service
public class DefaultReputationService implements ReputationService {

    @Override
    public ReputationScore getScore(String address) {
        // TODO: 实现评分查询逻辑（缓存优先 → 回源数据库 → 默认分）
        ReputationScore score = new ReputationScore();
        score.setAddress(address);
        score.setScore(0);
        score.setGrade(ReputationScore.Grade.C);
        return score;
    }

    @Override
    public ReputationScore updateScore(String address, Object event) {
        // TODO: 实现事件驱动评分更新逻辑（事件分类 → 加减分 → 等级重算 → 落库）
        ReputationScore score = new ReputationScore();
        score.setAddress(address);
        return score;
    }

    @Override
    public java.util.List<String> getHistory(String address) {
        // TODO: 实现历史回溯逻辑（按时间倒序返回事件流）
        return Collections.emptyList();
    }
}