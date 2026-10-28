package org.nexus.bridge;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ShedLock 方法签名守卫（PLAN-056）。
 *
 * <p>PLAN-055 实测：ShedLock 4.x 拒绝 primitive 返回类型的 {@code @SchedulerLock}
 * 方法——AOP 在调用前抛 {@code LockingNotSupportedException}，调度循环
 * 从未执行（而单测用 {@code new} + 手工调用绕开 AOP，所以全绿）。</p>
 *
 * <p>本测试调用 {@code scripts/security/check-shedlock-eligibility.py}，
 * 锁定"nexus-bridge 不存在 primitive 返回的 @SchedulerLock 方法"——
 * 新增此类方法门禁即红。</p>
 */
class ShedLockEligibilityTest {

    /** 从仓库根定位（多模块构建下 tests 运行目录可能是模块目录）。 */
    private static Path locateRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path script = dir.resolve("scripts/security/check-shedlock-eligibility.py");
            if (Files.isRegularFile(script)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    void bridgeHasNoPrimitiveReturningSchedulerLockMethod() throws Exception {
        Path root = locateRepoRoot();
        assertNotNull(root, "未找到 scripts/security/check-shedlock-eligibility.py（仓库根定位失败）");

        Path script = root.resolve("scripts/security/check-shedlock-eligibility.py");
        Path src = root.resolve("nexus-bridge/src/main/java");
        assertTrue(Files.isDirectory(src), "未找到 nexus-bridge/src/main/java");

        ProcessBuilder pb = new ProcessBuilder(
                "python", script.toString(), "--path", src.toString())
                .directory(root.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();

        if (code != 0) {
            System.err.println(output);
            fail("nexus-bridge 存在 primitive 返回的 @SchedulerLock 方法（ShedLock 4.x 会拒绝，"
                    + "循环将永不执行）；改为 void 或包装类型（PLAN-055/056）");
        }
        // 至少应扫到 4 个 @SchedulerLock 方法（防止移码/规则失灵后假绿）
        String text = output.toString();
        assertTrue(text.contains("@SchedulerLock 方法: 4"),
                "盘点数量应为 4（relay 2 + 保险基金 1 + saga 恢复 1），实际输出：\n" + text);
        assertEquals(0, code);
    }
}
