package org.nexus.walletsvc.custody;

import org.nexus.sdk.wallet.WalletTransactionRequest;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity.PendingStatus;
import org.nexus.walletsvc.repository.CustodyTransferPendingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 托管出金待处置记录写入器（独立 Bean）。
 *
 * <p>之所以单独成 Bean 而不是 {@code DefaultCustodyService} 的私有方法：
 * 本写入必须在 {@code REQUIRES_NEW} 独立事务里提交——{@code AWAITING_APPROVAL}
 * 分支随后要在外层抛异常回滚冷热余额，若同处一个事务，这条留痕会被一起抹掉。
 * {@code @Transactional} 在同类自调用上不生效（Spring 代理被绕过，
 * 见 {@code ThreePhaseExecutionTemplate} 的同类审计教训），故必须是跨 Bean 调用。</p>
 */
@Component
public class CustodyTransferPendingRecorder {

    private static final Logger log = LoggerFactory.getLogger(CustodyTransferPendingRecorder.class);

    /** 与 V5 DDL 的列宽一致：超长会整行插入失败，反而丢掉留痕，所以按列宽截断。 */
    private static final int MAX_REQUEST_KEY = 128;
    private static final int MAX_TYPE = 16;
    private static final int MAX_PURPOSE = 128;
    private static final int MAX_ADDRESS = 128;
    private static final int MAX_CURRENCY = 16;
    private static final int MAX_APPROVAL_ID = 64;
    private static final int MAX_NOTE = 512;

    private final CustodyTransferPendingRepository repository;

    public CustodyTransferPendingRecorder(CustodyTransferPendingRepository repository) {
        this.repository = repository;
    }

    /**
     * 以独立事务登记一条待处置记录（按 {@code requestId} 幂等 upsert）。
     *
     * <p>本方法不改变资金决策：调用方即便在此抛出也必须继续原来的回滚 / 不回滚动作，
     * 因此列宽做了截断，避免超长字符串让整行写入失败。</p>
     *
     * @param request    发给执行通道的原始请求（其 {@code requestId} 即幂等键）
     * @param status     待处置状态
     * @param approvalId 签名侧审批请求 ID，可为 null
     * @param note       原因说明，可为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(WalletTransactionRequest request, PendingStatus status,
                       String approvalId, String note) {
        if (request == null || status == null) {
            log.error("托管出金待处置登记被跳过（缺少请求或状态）: request={}, status={}", request, status);
            return;
        }
        String requestKey = request.getRequestId();
        if (requestKey == null || requestKey.isBlank()) {
            log.error("托管出金待处置无法登记（请求缺少 requestId，需人工按下列信息核对链上）: "
                            + "type={}, address={}, amount={}, approvalId={}, note={}",
                    request.getType(), request.getToAddress(), request.getAmount(), approvalId, note);
            return;
        }
        CustodyTransferPendingEntity entity = repository.findByRequestKey(requestKey)
                .orElseGet(CustodyTransferPendingEntity::new);
        entity.setRequestKey(truncate(requestKey, MAX_REQUEST_KEY));
        entity.setTransferType(truncate(String.valueOf(request.getType()), MAX_TYPE));
        entity.setPurpose(truncate(request.getMemo(), MAX_PURPOSE));
        entity.setAddress(truncate(request.getToAddress(), MAX_ADDRESS));
        entity.setAmount(request.getAmount());
        entity.setCurrency(truncate(request.getAsset(), MAX_CURRENCY));
        entity.setStatus(status);
        entity.setApprovalId(truncate(approvalId, MAX_APPROVAL_ID));
        entity.setErrorNote(truncate(note, MAX_NOTE));
        repository.save(entity);
        log.error("托管出金待处置已登记（需人工处置）: requestKey={}, type={}, address={}, amount={}, "
                        + "status={}, approvalId={}, note={}",
                requestKey, request.getType(), request.getToAddress(), request.getAmount(), status, approvalId, note);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
