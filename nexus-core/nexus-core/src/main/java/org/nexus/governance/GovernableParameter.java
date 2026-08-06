package org.nexus.governance;

import java.math.BigDecimal;

/**
 * 可治理参数实体。
 *
 * <p>描述单个链上可治理参数的元信息与当前值：
 * 名称、类型、取值范围、默认值、当前值、生效策略与敏感度。</p>
 *
 * <p>该实体为可变对象，{@link #currentValue} 在治理执行器应用变更时被更新。
 * 元信息（name/type/min/max/default/policy/sensitivity）在注册时确定，运行期不可变。</p>
 *
 * @since 1.3
 */
public class GovernableParameter {

    /** 参数名 */
    private final String name;

    /** 参数类型 */
    private final ParameterType type;

    /** 最小值（含） */
    private final BigDecimal minValue;

    /** 最大值（含） */
    private final BigDecimal maxValue;

    /** 默认值 */
    private final BigDecimal defaultValue;

    /** 当前值 */
    private volatile BigDecimal currentValue;

    /** 生效策略 */
    private final EffectivePolicy effectivePolicy;

    /** 敏感度 */
    private final ParameterSensitivity sensitivity;

    /**
     * 构造可治理参数。
     *
     * @param name            参数名
     * @param type            参数类型
     * @param minValue        最小值
     * @param maxValue        最大值
     * @param defaultValue    默认值（同时作为初始当前值）
     * @param effectivePolicy 生效策略
     * @param sensitivity     敏感度
     */
    public GovernableParameter(String name, ParameterType type,
                               BigDecimal minValue, BigDecimal maxValue, BigDecimal defaultValue,
                               EffectivePolicy effectivePolicy, ParameterSensitivity sensitivity) {
        this.name = name;
        this.type = type;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.defaultValue = defaultValue;
        this.currentValue = defaultValue;
        this.effectivePolicy = effectivePolicy;
        this.sensitivity = sensitivity;
    }

    public String getName() {
        return name;
    }

    public ParameterType getType() {
        return type;
    }

    public BigDecimal getMinValue() {
        return minValue;
    }

    public BigDecimal getMaxValue() {
        return maxValue;
    }

    public BigDecimal getDefaultValue() {
        return defaultValue;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    /**
     * 设置当前值。仅由 {@link GovernableParameterRegistry} 在校验通过后调用。
     *
     * @param currentValue 新的当前值
     */
    void setCurrentValue(BigDecimal currentValue) {
        this.currentValue = currentValue;
    }

    public EffectivePolicy getEffectivePolicy() {
        return effectivePolicy;
    }

    public ParameterSensitivity getSensitivity() {
        return sensitivity;
    }

    @Override
    public String toString() {
        return "GovernableParameter{name='" + name + "', type=" + type
                + ", min=" + minValue + ", max=" + maxValue
                + ", default=" + defaultValue + ", current=" + currentValue
                + ", policy=" + effectivePolicy + ", sensitivity=" + sensitivity + '}';
    }
}