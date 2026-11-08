package org.nexus.service;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.controller.BatchTransferController;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量转账写端点收口测试。
 *
 * <p>支付扩展（0x10–0x1a）保留未启用：{@code POST /batch/transfer} 有效入参
 * 返回 5010（HTTP 501），入参校验失败优先返回 {@link APIResult#FAIL}。</p>
 */
class BatchTransferWriteOffTest {

    private static final String FROM_PUBKEY = "aa";
    private static final String PRIKEY = "bb";

    private final BatchTransferService service = new BatchTransferService();

    private static List<TransferItem> validItems() {
        return List.of(new TransferItem("NEX1recipient", 100L));
    }

    @Test
    void batchTransferReturnsNotEnabled() {
        APIResult result = service.batchTransfer(FROM_PUBKEY, validItems(), PRIKEY, 0L);

        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED, result.getCode());
        assertTrue(result.getMessage().contains("0x10-0x1a"),
                "message must state that 0x10-0x1a is reserved: " + result.getMessage());
    }

    @Test
    void emptyFromPubkeyFailsBeforeNotEnabled() {
        APIResult result = service.batchTransfer("", validItems(), PRIKEY, 0L);

        assertEquals(APIResult.FAIL, result.getCode());
    }

    @Test
    void emptyRecipientsFailsBeforeNotEnabled() {
        APIResult result = service.batchTransfer(FROM_PUBKEY, List.of(), PRIKEY, 0L);

        assertEquals(APIResult.FAIL, result.getCode());
    }

    @Test
    void emptyPrikeyFailsBeforeNotEnabled() {
        APIResult result = service.batchTransfer(FROM_PUBKEY, validItems(), "", 0L);

        assertEquals(APIResult.FAIL, result.getCode());
    }

    @Test
    void nonPositiveAmountFailsBeforeNotEnabled() {
        List<TransferItem> items = List.of(new TransferItem("NEX1recipient", 0L));
        APIResult result = service.batchTransfer(FROM_PUBKEY, items, PRIKEY, 0L);

        assertEquals(APIResult.FAIL, result.getCode());
    }

    @Test
    void controllerMapsNotEnabledToHttp501() throws Exception {
        BatchTransferController controller = new BatchTransferController();
        inject(controller, "batchTransferService", service);

        Object result = controller.batchTransfer(validItems(), FROM_PUBKEY, PRIKEY, 0L);

        ResponseEntity<?> entity = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(501, entity.getStatusCode().value());
        APIResult body = assertInstanceOf(APIResult.class, entity.getBody());
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED, body.getCode());
    }

    @Test
    void controllerKeepsValidationFailureAsPlainResult() throws Exception {
        BatchTransferController controller = new BatchTransferController();
        inject(controller, "batchTransferService", service);

        Object result = controller.batchTransfer(validItems(), "", PRIKEY, 0L);

        APIResult body = assertInstanceOf(APIResult.class, result);
        assertEquals(APIResult.FAIL, body.getCode());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
