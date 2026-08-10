package org.nexus.oracle.governance.execution;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注仅用于测试可见的方法 / 字段（GOV-P1-02）。
 *
 * <p>被此注解标注的成员在生产代码中不应被调用，仅用于单元测试访问
 * 包级私有成员。类似于 Guava 的 {@code com.google.common.annotations.VisibleForTesting}。
 *
 * <p>使用场景：当方法需要降低可见性（如从 {@code public} 改为包级私有）
 * 但测试代码仍需访问时，标注此注解说明意图。
 *
 * @since 2.1.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface VisibleForTesting {

    /**
     * 说明为何此成员需要测试可见性（可选）。
     *
     * @return 说明文本
     */
    String value() default "";
}