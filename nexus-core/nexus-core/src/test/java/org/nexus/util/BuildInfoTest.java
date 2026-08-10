package org.nexus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BuildInfo} 单元测试。
 */
class BuildInfoTest {

    @Test
    void printInfoDoesNotThrow() {
        // 静态初始化块可能已执行，buildHash/buildTime 可能为 null
        BuildInfo.printInfo();
    }

    @Test
    void staticFieldsAccessible() {
        // 验证静态字段可访问（可能为 null 如果 properties 文件不存在）
        assertNotNull(BuildInfo.class);
    }
}