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
 * ShedLock 方法签名守卫（PLAN-056 / PLAN-061）。
 *
 * <p>PLAN-055 实测：ShedLock 4.x 拒绝 primitive 返回类型的 {@code @SchedulerLock}
 * 方法——AOP 在调用前抛 {@code LockingNotSupportedException}，调度循环
 * 从未执行（而单测用 {@code new} + 手工调用绕开 AOP，所以全绿）。</p>
 *
 * <p>PLAN-056 落地时本测试只扫 {@code nexus-bridge/src/main/java}；而全仓共
 * 33 处 {@code @SchedulerLock}（bridge 4 + gateway 29），gateway 的 29 处
 * 从未被本测试看过（PLAN-059 已把 workflow 与脚本缺省扩到全仓）。PLAN-061
 * 把本测试也扩到全仓——本测试的定位正是"开发者本地 {@code gradle test}
 * 也能拦"，覆盖面不该比 workflow 窄。</p>
 *
 * <p>仓库根定位沿用 {@code scripts/security/check-shedlock-eligibility.py}
 * 的上溯查找（不依赖任何跨模块源码路径）。</p>
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
    void repoWideHasNoPrimitiveReturningSchedulerLockMethod() throws Exception {
        Path root = locateRepoRoot();
        assertNotNull(root, "未找到 scripts/security/check-shedlock-eligibility.py（仓库根定位失败）");

        Path script = root.resolve("scripts/security/check-shedlock-eligibility.py");
        // PLAN-061：扫仓库根而不是 nexus-bridge/src/main/java。
        assertTrue(Files.isDirectory(root.resolve("nexus-bridge/src/main/java")),
                "未找到 nexus-bridge/src/main/java（仓库根定位可疑）");

        ProcessBuilder pb = new ProcessBuilder(
                "python", script.toString(), "--path", root.toString())
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
            fail("存在 primitive 返回的 @SchedulerLock 方法（ShedLock 4.x 会拒绝，"
                    + "循环将永不执行）；改为 void 或包装类型（PLAN-055/056/061）");
        }
        String text = output.toString();

        // 防假绿一：盘点数量不能低于 bridge 自己的 4 个（防止脚本失灵后扫出 0 也算过）
        assertTrue(text.contains("@SchedulerLock 方法:")
                        && scannedCount(text) >= 4,
                "盘点数量应 >= 4（relay 2 + 保险基金 1 + saga 恢复 1），实际输出：\n" + text);

        // 防假绿二（PLAN-061 的要点）：必须真的扫到了 gateway。
        // PLAN-056 时本测试只传 nexus-bridge/src/main/java，而全仓共 33 处
        // @SchedulerLock（bridge 4 + gateway 29）——若哪天又退回模块内扫描，
        // 这条断言会先红，而不是等到 PLAN-055 那种"从未执行"在生产上爆。
        assertTrue(text.contains("nexus-bridge"),
                "盘点输出里应出现 nexus-bridge 的方法（覆盖回归），实际输出：\n" + text);
        assertTrue(text.contains("nexus-gateway"),
                "盘点输出里应出现 nexus-gateway 的方法——PLAN-061 起本测试覆盖全仓，"
                        + "若只看到 bridge 说明覆盖面又退回了模块内，实际输出：\n" + text);
        assertEquals(0, code);
    }

    /** 从脚本输出里取 "…方法: N" 的 N（取不到返回 0）。 */
    private static int scannedCount(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@SchedulerLock 方法:\\s*(\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
