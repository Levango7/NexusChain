package org.nexus.gateway.fundreport;

import java.math.BigDecimal;

/** 出款失败异常（调用方据此补偿：把已扣的商户余额退回）。 */
public class PayoutException extends RuntimeException {

    public PayoutException(String message) {
        super(message);
    }

    public PayoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
