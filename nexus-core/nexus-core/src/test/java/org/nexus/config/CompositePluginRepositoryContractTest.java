package org.nexus.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** included build 的插件解析必须有官方 Central 与镜像回退，不能只剩 Portal 单点。 */
class CompositePluginRepositoryContractTest {

    @Test
    void consortiumDeclaresPortalCentralAndHuaweiFallback() throws IOException {
        String block = pluginManagementBlock("nexus-consortium");
        assertTrue(block.contains("gradlePluginPortal()"), "保留插件 Portal 官方源");
        assertTrue(block.contains("mavenCentral()"), "Maven Central 必须成为显式回退");
        assertTrue(block.contains("name = 'huaweiPlugins'"), "镜像仓库必须命名以便日志归因");
        assertTrue(block.contains("url = 'https://repo.huaweicloud.com/repository/maven/'"),
                "保留已在其他 composite 实跑的华为云回退");
    }

    @Test
    void consortiumMatchesSiblingCompositeRepositoryPolicy() throws IOException {
        assertEquals(repositoryDeclarations("nexus-settlement"),
                repositoryDeclarations("nexus-consortium"),
                "只比较可执行仓库声明与顺序；CRLF/LF 和注释不属于解析语义");
    }

    private static List<String> repositoryDeclarations(String project) throws IOException {
        return pluginManagementBlock(project).lines()
                .map(String::trim)
                .filter(line -> line.equals("gradlePluginPortal()")
                        || line.equals("mavenCentral()")
                        || line.equals("name = 'huaweiPlugins'")
                        || line.equals("url = 'https://repo.huaweicloud.com/repository/maven/'"))
                .toList();
    }

    private static String pluginManagementBlock(String project) throws IOException {
        String settings = Files.readString(Path.of("..", "..", project, "settings.gradle"))
                .replace("\r\n", "\n").replace("\r", "\n");
        int start = settings.indexOf("pluginManagement {");
        assertTrue(start >= 0, project + " 缺少显式 pluginManagement 回退链");
        int depth = 0;
        for (int i = settings.indexOf('{', start); i < settings.length(); i++) {
            char c = settings.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) {
                return settings.substring(start, i + 1).trim();
            }
        }
        throw new AssertionError(project + " 的 pluginManagement 块未闭合");
    }
}
