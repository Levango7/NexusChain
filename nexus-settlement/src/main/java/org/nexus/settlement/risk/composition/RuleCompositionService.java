package org.nexus.settlement.risk.composition;

import org.nexus.settlement.risk.RiskDecision;
import org.nexus.settlement.risk.RiskScoreResult;
import org.nexus.settlement.risk.RiskScoringRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则组合服务 — 支持 AND/OR 组合逻辑、优先级排序、处置动作映射。
 *
 * <p>核心能力：
 * <ul>
 *   <li>加载已启用的规则组合（按优先级排序）</li>
 *   <li>基于评分结果评估组合表达式（AND/OR 逻辑）</li>
 *   <li>将组合触发映射为处置动作（ALERT/BLOCK/MANUAL_REVIEW/CAPTURE）</li>
 *   <li>组合配置的 CRUD 管理</li>
 * </ul>
 * </p>
 */
@Service
public class RuleCompositionService {

    private static final Logger log = LoggerFactory.getLogger(RuleCompositionService.class);

    private final RuleCompositionRepository repository;

    public RuleCompositionService(RuleCompositionRepository repository) {
        this.repository = repository;
    }

    /**
     * 加载所有已启用的规则组合，按优先级排序。
     *
     * @return 已启用的规则组合列表
     */
    public List<RuleComposition> loadEnabledCompositions() {
        return repository.findByEnabledTrueOrderByPriorityAsc();
    }

    /**
     * 基于评分结果评估所有已启用的规则组合。
     *
     * <p>评估逻辑：
     * <ol>
     *   <li>按优先级顺序遍历所有组合</li>
     *   <li>对每个组合，解析表达式中的规则 ID</li>
     *   <li>AND 逻辑：所有规则评分都超过阈值时触发</li>
     *   <li>OR 逻辑：任意规则评分超过阈值时触发</li>
     *   <li>返回第一个触发的组合的处置动作</li>
     * </ol>
     * </p>
     *
     * @param scoreResult 评分结果（包含各规则评分明细）
     * @return 处置动作（如果无组合触发则返回 null）
     */
    public RuleComposition.ActionType evaluateCompositions(RiskScoreResult scoreResult) {
        if (scoreResult == null) {
            return null;
        }

        List<RuleComposition> compositions = loadEnabledCompositions();
        Map<String, Integer> ruleScores = scoreResult.getRuleScores();

        for (RuleComposition composition : compositions) {
            if (evaluateComposition(composition, ruleScores)) {
                log.info("Rule composition triggered: name={}, type={}, action={}",
                        composition.getCompositionName(), composition.getCompositionType(),
                        composition.getAction());
                return composition.getAction();
            }
        }

        return null;
    }

    /**
     * 评估单个组合是否触发。
     *
     * @param composition 组合配置
     * @param ruleScores  各规则评分明细
     * @return 是否触发
     */
    private boolean evaluateComposition(RuleComposition composition, Map<String, Integer> ruleScores) {
        List<String> ruleIds = parseExpression(composition.getExpression());
        if (ruleIds.isEmpty()) {
            return false;
        }

        int threshold = composition.getScoreThreshold() != null ? composition.getScoreThreshold() : 50;

        switch (composition.getCompositionType()) {
            case AND:
                // 所有规则评分都超过阈值时触发
                return ruleIds.stream().allMatch(ruleId -> {
                    Integer score = ruleScores.get(ruleId);
                    return score != null && score >= threshold;
                });
            case OR:
                // 任意规则评分超过阈值时触发
                return ruleIds.stream().anyMatch(ruleId -> {
                    Integer score = ruleScores.get(ruleId);
                    return score != null && score >= threshold;
                });
            default:
                return false;
        }
    }

    /**
     * 解析组合表达式，提取规则 ID 列表。
     * <p>
     * 支持格式：
     * <ul>
     *   <li>"IP_SCORE & REGION_SCORE" → [IP_SCORE, REGION_SCORE]</li>
     *   <li>"IP_SCORE | BLACKLIST_SCORE" → [IP_SCORE, BLACKLIST_SCORE]</li>
     *   <li>"IP_SCORE,REGION_SCORE" → [IP_SCORE, REGION_SCORE]</li>
     * </ul>
     * </p>
     *
     * @param expression 组合表达式
     * @return 规则 ID 列表
     */
    public List<String> parseExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return Collections.emptyList();
        }

        // 支持 &、|、, 作为分隔符
        return Arrays.stream(expression.split("[&|,]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 将处置动作映射为风控决策。
     *
     * @param action 处置动作
     * @return 风控决策
     */
    public RiskDecision mapActionToDecision(RuleComposition.ActionType action) {
        if (action == null) {
            return RiskDecision.APPROVED;
        }
        return switch (action) {
            case ALERT -> RiskDecision.APPROVED; // 告警但放行
            case BLOCK -> RiskDecision.REJECTED;
            case MANUAL_REVIEW -> RiskDecision.PENDING_REVIEW;
            case CAPTURE -> RiskDecision.APPROVED; // 捕获并放行
        };
    }

    // --- CRUD 管理 ---

    /**
     * 创建新的规则组合。
     *
     * @param composition 组合配置
     * @return 保存后的组合配置
     */
    @Transactional
    public RuleComposition createComposition(RuleComposition composition) {
        if (repository.existsByCompositionName(composition.getCompositionName())) {
            throw new IllegalArgumentException("Composition name already exists: " + composition.getCompositionName());
        }
        RuleComposition saved = repository.save(composition);
        log.info("Created rule composition: name={}, type={}, action={}",
                saved.getCompositionName(), saved.getCompositionType(), saved.getAction());
        return saved;
    }

    /**
     * 更新规则组合。
     *
     * @param id          组合 ID
     * @param composition 更新内容
     * @return 更新后的组合配置
     */
    @Transactional
    public RuleComposition updateComposition(Long id, RuleComposition composition) {
        RuleComposition existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Composition not found: id=" + id));

        if (composition.getCompositionName() != null) {
            existing.setCompositionName(composition.getCompositionName());
        }
        if (composition.getCompositionType() != null) {
            existing.setCompositionType(composition.getCompositionType());
        }
        if (composition.getExpression() != null) {
            existing.setExpression(composition.getExpression());
        }
        if (composition.getPriority() != null) {
            existing.setPriority(composition.getPriority());
        }
        if (composition.getAction() != null) {
            existing.setAction(composition.getAction());
        }
        if (composition.getEnabled() != null) {
            existing.setEnabled(composition.getEnabled());
        }
        if (composition.getScoreThreshold() != null) {
            existing.setScoreThreshold(composition.getScoreThreshold());
        }

        RuleComposition saved = repository.save(existing);
        log.info("Updated rule composition: id={}, name={}", saved.getId(), saved.getCompositionName());
        return saved;
    }

    /**
     * 删除规则组合。
     *
     * @param id 组合 ID
     */
    @Transactional
    public void deleteComposition(Long id) {
        repository.deleteById(id);
        log.info("Deleted rule composition: id={}", id);
    }

    /**
     * 获取所有规则组合。
     *
     * @return 所有规则组合列表
     */
    public List<RuleComposition> findAllCompositions() {
        return repository.findAll();
    }

    /**
     * 根据组合名称查找。
     *
     * @param compositionName 组合名称
     * @return 组合配置（可选）
     */
    public Optional<RuleComposition> findByCompositionName(String compositionName) {
        return repository.findByCompositionName(compositionName);
    }
}