package org.nexus.consortium.util;

import org.junit.jupiter.api.Test;
import org.nexus.consortium.exception.ApplicationException;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileUtils 单元测试。
 * 覆盖 getResource 的正常与异常路径。
 */
public class FileUtilsTest {

    @Test
    public void testGetResourceNotFound() {
        assertThrows(ApplicationException.class, () -> {
            FileUtils.getResource("nonexistent-file-12345.txt");
        });
    }

    @Test
    public void testGetResourceClasspath() throws ApplicationException {
        Resource resource = FileUtils.getResource("application.yml");
        assertNotNull(resource);
    }

}