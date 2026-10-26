package org.nexus.signing.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * (b2) 止血裁决的豁免名单用例：{@code nexus.approval.exempt-from-pubkeys}。
 *
 * <p>背景：wallet 执行腿与 signing 大额审批构成两层重复审批，且 wallet 侧重试
 * 每次都会新建一条悬空审批（留言板 20261025-0415 §五 / 20261025-0830 §一）。
 * 用户拍板：按转出地址豁免（(b2)）止血，目标态 (a)。列入名单的 fromPubkey 跳过
 * signing 大额审批——业务审批在 wallet 多签层，控制不丢失；名单未配置时行为
 * 必须与旧版完全一致（fail-closed）。</p>
 */
class SigningApprovalExemptFromPubkeysTest {

    private static final BigDecimal LARGE = new BigDecimal("10000");

    private static SigningApprovalService serviceWithExempt(String exemptList) {
        SigningApprovalService service = new SigningApprovalService(
                mock(MpcApprovalPolicy.class),
                mock(AuditLogService.class),
                new MapApprovalStore(new ConcurrentHashMap<>()),
                null);
        ReflectionTestUtils.setField(service, "largeAmountThreshold", LARGE);
        ReflectionTestUtils.setField(service, "exemptFromPubkeys", exemptList);
        return service;
    }

    @Test
    @DisplayName("名单内地址：大额也豁免（不触发 signing 审批）")
    void exemptAddressSkipsApprovalEvenForLargeAmount() {
        SigningApprovalService service = serviceWithExempt("PlatformWalletPubKey, other");
        assertFalse(service.requiresApproval(new BigDecimal("999999"), "USDT", "PlatformWalletPubKey"),
                "豁免地址的大额转账不应再触发 signing 审批（wallet 多签已在业务层审批）");
    }

    @Test
    @DisplayName("名单外地址：维持旧语义（大额仍需审批）")
    void nonExemptAddressStillRequiresApproval() {
        SigningApprovalService service = serviceWithExempt("PlatformWalletPubKey");
        assertTrue(service.requiresApproval(LARGE, "USDT", "SomeoneElse"),
                "名单外地址必须维持大额审批，豁免面不得扩大");
    }

    @Test
    @DisplayName("fromPubkey 为 null 或两参旧签名：与旧版等价（大额仍需审批）")
    void nullFromPubkeyKeepsLegacySemantics() {
        SigningApprovalService service = serviceWithExempt("PlatformWalletPubKey");
        assertTrue(service.requiresApproval(LARGE, "USDT", null));
        assertTrue(service.requiresApproval(LARGE, "USDT"),
                "两参旧签名必须保留且行为不变");
    }

    @Test
    @DisplayName("名单未配置/空白：任何地址都不豁免（fail-closed，行为同旧版）")
    void unconfiguredListExemptsNothing() {
        SigningApprovalService service = serviceWithExempt("   ");
        assertTrue(service.requiresApproval(LARGE, "USDT", "PlatformWalletPubKey"),
                "名单为空时不得产生任何豁免（默认部署行为必须与旧版一致）");
        assertTrue(service.requiresApproval(LARGE, "USDT", null));
    }

    @Test
    @DisplayName("匹配按公钥原文精确（区分大小写）+ 条目与入参均 trim 空白")
    void matchingIsExactAfterTrim() {
        SigningApprovalService service = serviceWithExempt(" platformkey ");
        assertFalse(service.requiresApproval(LARGE, "USDT", " platformkey "),
                "两侧空白应 trim 后匹配");
        assertTrue(service.requiresApproval(LARGE, "USDT", "PlatformKey"),
                "公钥匹配区分大小写，不得做大小写归一");
    }

    @Test
    @DisplayName("豁免地址走到 createApprovalRequest 也直接返回 null（不建悬空审批单）")
    void exemptAddressCreatesNoApprovalRequest() {
        SigningApprovalService service = serviceWithExempt("platformkey");
        String approvalId = service.createApprovalRequest(
                "platformkey", "toHash", new BigDecimal("999999"), "USDT", "tester", "127.0.0.1");
        assertNull(approvalId,
                "豁免地址不得创建悬空审批单——悬空审批正是 (b2) 要消除的缺陷本体");
    }
}
