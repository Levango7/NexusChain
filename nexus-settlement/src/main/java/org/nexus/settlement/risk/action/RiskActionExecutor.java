package org.nexus.settlement.risk.action;

import org.nexus.settlement.risk.RiskDecision;
import org.nexus.settlement.risk.RiskScoreResult;
import org.nexus.settlement.risk.RiskTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * 风控处置执行器 — 执行处置动作并记录处置结果。
 *
 * <p>支持四种处置动作：
 * <ul>
 *   <li>{@link RiskActionRecord.ActionType#ALERT} — 告警：记录告警信息，交易继续放行</li>
 *   <li>{@link RiskActionRecord.ActionType#BLOCK} — 阻断：拒绝交易，记录阻断原因</li>
 *   <li>{@link RiskActionRecord.ActionType#MANUAL_REVIEW} — 人工审核：暂停交易，等待人工复核</li>
 *   <li>{@link RiskActionRecord.ActionType#CAPTURE} — 捕获并放行：记录风险信息但允许交易通过</li>
 * </ul>
 * </p>
 *
 * <p>处置流程闭环：
 * <ol>
 *   <li>接收评分结果和处置动作</li>
 *   <li>执行对应的处置逻辑</li>
 *   <li>生成 RiskActionRecord 记录处置结果</li>
 *   <li>返回最终的风控决策</li>
 * </ol>
 * </p>
 */
@Service
public class RiskActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(RiskActionExecutor.class);

    private final RiskActionRecordRepository recordRepository;

    public RiskActionExecutor(RiskActionRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * 执行处置动作。
     *
     * @param actionType  处置动作类型
     * @param scoreResult 评分结果
     * @param transaction 交易对象
     * @return 风控决策
     */
    public RiskDecision executeAction(RiskActionRecord.ActionType actionType,
                                      RiskScoreResult scoreResult,
                                      RiskTransaction transaction) {
        if (actionType == null) {
            log.debug("No action type specified, defaulting to APPROVED");
            return RiskDecision.APPROVED;
        }

        log.info("Executing risk action: type={}, totalScore={}", actionType, 
                scoreResult != null ? scoreResult.getTotalScore() : "N/A");

        RiskDecision decision = mapActionToDecision(actionType);

        // 创建处置记录
        RiskActionRecord record = createRecord(actionType, scoreResult, transaction, decision);

        // 执行处置逻辑
        switch (actionType) {
            case ALERT:
                executeAlert(record, scoreResult, transaction);
                break;
            case BLOCK:
                executeBlock(record, scoreResult, transaction);
                break;
            case MANUAL_REVIEW:
                executeManualReview(record, scoreResult, transaction);
                break;
            case CAPTURE:
                executeCapture(record, scoreResult, transaction);
                break;
        }

        // 保存处置记录
        recordRepository.save(record);
        log.info("Risk action executed: type={}, decision={}, recordId={}",
                actionType, decision, record.getId());

        return decision;
    }

    /**
     * 执行告警动作 — 记录告警信息，交易继续放行。
     */
    private void executeAlert(RiskActionRecord record, RiskScoreResult scoreResult, RiskTransaction transaction) {
        record.setDescription(buildDescription("Alert triggered", scoreResult, transaction));
        record.setActionStatus(RiskActionRecord.ActionStatus.EXECUTED);
        record.setCompletedAt(LocalDateTime.now());
        log.warn("Risk ALERT: score={}, transaction={}",
                scoreResult != null ? scoreResult.getTotalScore() : "N/A", transaction);
    }

    /**
     * 执行阻断动作 — 拒绝交易，记录阻断原因。
     */
    private void executeBlock(RiskActionRecord record, RiskScoreResult scoreResult, RiskTransaction transaction) {
        record.setDescription(buildDescription("Transaction blocked due to high risk", scoreResult, transaction));
        record.setActionStatus(RiskActionRecord.ActionStatus.EXECUTED);
        record.setCompletedAt(LocalDateTime.now());
        log.warn("Risk BLOCK: score={}, transaction={}",
                scoreResult != null ? scoreResult.getTotalScore() : "N/A", transaction);
    }

    /**
     * 执行人工审核动作 — 暂停交易，等待人工复核。
     */
    private void executeManualReview(RiskActionRecord record, RiskScoreResult scoreResult, RiskTransaction transaction) {
        record.setDescription(buildDescription("Transaction pending manual review", scoreResult, transaction));
        record.setActionStatus(RiskActionRecord.ActionStatus.PENDING);
        log.warn("Risk MANUAL_REVIEW: score={}, transaction={}",
                scoreResult != null ? scoreResult.getTotalScore() : "N/A", transaction);
    }

    /**
     * 执行捕获并放行动作 — 记录风险信息但允许交易通过。
     */
    private void executeCapture(RiskActionRecord record, RiskScoreResult scoreResult, RiskTransaction transaction) {
        record.setDescription(buildDescription("Risk captured and transaction released", scoreResult, transaction));
        record.setActionStatus(RiskActionRecord.ActionStatus.EXECUTED);
        record.setCompletedAt(LocalDateTime.now());
        log.info("Risk CAPTURE: score={}, transaction={}",
                scoreResult != null ? scoreResult.getTotalScore() : "N/A", transaction);
    }

    /**
     * 创建处置记录。
     */
    private RiskActionRecord createRecord(RiskActionRecord.ActionType actionType,
                                          RiskScoreResult scoreResult,
                                          RiskTransaction transaction,
                                          RiskDecision decision) {
        RiskActionRecord record = new RiskActionRecord();
        record.setActionType(actionType);
        record.setRiskScore(scoreResult != null ? scoreResult.getTotalScore() : null);
        record.setRiskDecision(decision != null ? decision.name() : null);

        if (transaction != null) {
            record.setMerchantId(transaction.getMerchantId());
        }

        if (scoreResult != null) {
            String triggeredRules = scoreResult.getRuleScores().entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(","));
            record.setTriggeredRules(triggeredRules);
        }

        return record;
    }

    /**
     * 构建处置描述文本。
     */
    private String buildDescription(String prefix, RiskScoreResult scoreResult, RiskTransaction transaction) {
        StringBuilder sb = new StringBuilder(prefix);
        if (scoreResult != null) {
            sb.append(" [score=").append(scoreResult.getTotalScore()).append("]");
        }
        if (transaction != null) {
            sb.append(" [merchantId=").append(transaction.getMerchantId()).append("]");
        }
        return sb.toString();
    }

    /**
     * 将处置动作映射为风控决策。
     *
     * @param actionType 处置动作
     * @return 风控决策
     */
    public RiskDecision mapActionToDecision(RiskActionRecord.ActionType actionType) {
        if (actionType == null) {
            return RiskDecision.APPROVED;
        }
        return switch (actionType) {
            case ALERT -> RiskDecision.APPROVED; // 告警但放行
            case BLOCK -> RiskDecision.REJECTED;
            case MANUAL_REVIEW -> RiskDecision.PENDING_REVIEW;
            case CAPTURE -> RiskDecision.APPROVED; // 捕获并放行
        };
    }

    /**
     * 根据评分结果自动选择处置动作。
     *
     * @param scoreResult 评分结果
     * @return 处置动作
     */
    public RiskActionRecord.ActionType autoSelectAction(RiskScoreResult scoreResult) {
        if (scoreResult == null) {
            return RiskActionRecord.ActionType.CAPTURE;
        }

        int totalScore = scoreResult.getTotalScore();
        if (totalScore >= 80) {
            return RiskActionRecord.ActionType.BLOCK;
        } else if (totalScore >= 60) {
            return RiskActionRecord.ActionType.MANUAL_REVIEW;
        } else if (totalScore >= 30) {
            return RiskActionRecord.ActionType.ALERT;
        } else {
            return RiskActionRecord.ActionType.CAPTURE;
        }
    }
}