package org.nexus.consortium.dao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransactionDao 单元测试。
 * 覆盖接口定义与 JpaRepository 继承。
 */
public class TransactionDaoTest {

    @Test
    public void testInterfaceDefinition() {
        Class<?> clazz = TransactionDao.class;
        assertTrue(clazz.isInterface());
    }

    @Test
    public void testExtendsJpaRepository() {
        Class<?>[] interfaces = TransactionDao.class.getInterfaces();
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