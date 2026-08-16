package org.nexus.consortium.dao;

import org.junit.jupiter.api.Test;
import org.nexus.consortium.entity.Block;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlockDao 单元测试。
 * 覆盖接口定义与 JpaRepository 继承。
 */
public class BlockDaoTest {

    @Test
    public void testInterfaceDefinition() {
        Class<?> clazz = BlockDao.class;
        assertTrue(clazz.isInterface());
    }

    @Test
    public void testExtendsJpaRepository() {
        Class<?>[] interfaces = BlockDao.class.getInterfaces();
        boolean extendsJpa = false;
        for (Class<?> iface : interfaces) {
            if (iface.getName().contains("JpaRepository")) {
                extendsJpa = true;
                break;
            }
        }
        assertTrue(extendsJpa);
    }
}