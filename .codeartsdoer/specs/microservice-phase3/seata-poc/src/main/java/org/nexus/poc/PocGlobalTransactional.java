package org.nexus.poc;

import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

/**
 * 验证 {@code @GlobalTransactional} 注解可从 seata-spring-boot-starter 2.0.0 正确解析。
 *
 * <p>若 seata-spring-boot-starter 版本与 SpringBoot 3.2.5 不兼容，
 * 本类将编译失败（import io.seata.spring.annotation.GlobalTransactional 无法解析）。</p>
 *
 * <p>设计文档 §4.2.3 @GlobalTransactional 标注。</p>
 */
@Service
public class PocGlobalTransactional {

    /**
     * POC 全局事务方法（不实际执行，仅验证注解可编译）。
     *
     * @param orderId 订单 ID
     * @return 操作结果
     */
    @GlobalTransactional(name = "poc-tx", timeoutMills = 60000, rollbackFor = Exception.class)
    public String pocGlobalTx(Long orderId) {
        return "poc-ok-" + orderId;
    }
}