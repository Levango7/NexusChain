package org.nexus.consortium.dao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HeaderDao 单元测试。
 * 覆盖接口定义与 JpaRepository 继承。
 */
public class HeaderDaoTest {

    @Test
    public void testInterfaceDefinition() {
        Class<?> clazz = HeaderDao.class;
        assertTrue(clazz.isInterface());
    }

    @Test
    public void testExtendsJpaRepository() {
        Class<?>[] interfaces = HeaderDao.class.getInterfaces();
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