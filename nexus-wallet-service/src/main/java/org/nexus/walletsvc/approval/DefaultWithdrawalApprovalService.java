package org.nexus.walletsvc.approval;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * {@link WithdrawalApprovalService} 的默认骨架实现。
 *
 * <p>PoC 阶段：所有操作返回新建的 PENDING 请求，不执行实际审批状态机，
 * 仅用于保证钱包服务模块可独立编译与装配。完整迁移后将接入
 * DefaultWithdrawalApprovalService 真实逻辑。</p>
 */
@Service
public class DefaultWithdrawalApprovalService implements WithdrawalApprovalService {

    @Override
    public WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency) {
        // PoC：返回 PENDING 请求，不执行实际逻辑
        return new WithdrawalRequest("POC-" + System.nanoTime(), to, amount, currency);
    }

    @Override
    public WithdrawalRequest approve(String approvalId, String approverId) {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setApprovalId(approvalId);
        req.setStatus(WithdrawalRequest.WithdrawalStatus.PENDING);
        return req;
    }

    @Override
    public WithdrawalRequest reject(String approvalId, String approverId, String reason) {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setApprovalId(approvalId);
        req.setStatus(WithdrawalRequest.WithdrawalStatus.REJECTED);
        return req;
    }

    @Override
    public WithdrawalRequest executeApprovedWithdrawal(String approvalId) {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setApprovalId(approvalId);
        req.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
        return req;
    }
}