package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.repository.OrchestratedPaymentRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 支付回调服务 — 为回调控制器提供支付订单查询与状态更新能力，
 * 避免控制器直接访问 Repository（遵守架构分层规则）。
 */
@Service
public class PaymentCallbackService {

    private final OrchestratedPaymentRepository paymentRepository;

    public PaymentCallbackService(OrchestratedPaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * 根据支付订单 ID 查询。
     */
    public Optional<OrchestratedPayment> findById(String paymentId) {
        return paymentRepository.findById(paymentId);
    }

    /**
     * 保存支付订单（含状态更新）。
     */
    public OrchestratedPayment save(OrchestratedPayment payment) {
        return paymentRepository.save(payment);
    }
}