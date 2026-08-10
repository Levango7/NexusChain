package org.nexus.governance;

/**
 * 参数变更实体。
 *
 * <p>描述一次链上参数变更的旧值、新值与生效高度。</p>
 *
 * @since 1.2
 */
public class ParameterChange {

    /** 参数名 */
    private String parameterName;

    /** 旧值（字符串表示，便于通用化） */
    private String oldValue;

    /** 新值 */
    private String newValue;

    /** 生效高度 */
    private long effectiveHeight;

    public ParameterChange() {
    }

    public ParameterChange(String parameterName, String oldValue, String newValue, long effectiveHeight) {
        this.parameterName = parameterName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.effectiveHeight = effectiveHeight;
    }

    public String getParameterName() {
        return parameterName;
    }

    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public long getEffectiveHeight() {
        return effectiveHeight;
    }

    public void setEffectiveHeight(long effectiveHeight) {
        this.effectiveHeight = effectiveHeight;
    }
}