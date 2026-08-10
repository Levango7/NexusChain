package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link org.nexus.walletsvc.controller.WalletController} 集成测试
 * （Phase 4 任务 #74，设计文档 §4.6.2）。
 *
 * <p>使用 {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} + H2 内存数据库，
 * 通过 MockMvc 调用 REST 端点验证控制器 → Service → Repository → 数据库的完整链路。</p>
 *
 * <p>验证的端点：
 * <ul>
 *   <li>{@code GET /api/v1/wallet/health}：健康检查</li>
 *   <li>{@code GET /api/v1/wallet/whitelist/check}：白名单查询</li>
 *   <li>{@code POST /api/v1/wallet/whitelist/add}：加入白名单</li>
 *   <li>{@code POST /api/v1/wallet/whitelist/remove}：移出白名单</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/request}：发起提现</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/approve}：审批提现</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/execute}：执行提现</li>
 *   <li>{@code GET /api/v1/wallet/custody/balance}：托管余额查询</li>
 * </ul>
 * </p>
 *
 * <p>{@link SigningServiceFeignClient} 通过 {@code @MockBean} 模拟。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    @MockBean
    private SigningServiceFeignClient signingServiceClient;

    private static final String WHITELISTED_ADDR = "0xcontrollerTestAddr12345678901";

    @BeforeEach
    void setupWhitelist() {
        if (!whitelistEntryRepository.existsByAddress(WHITELISTED_ADDR)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(WHITELISTED_ADDR);
            entry.setMerchantId("merchant-controller-it");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }
    }

    @Test
    @DisplayName("GET /health: 返回服务状态")
    void health_returnsServiceStatus() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("nexus-wallet-service"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /whitelist/check: 已加白地址返回 whitelisted=true")
    void checkWhitelist_whitelistedAddressReturnsTrue() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/whitelist/check").param("address", WHITELISTED_ADDR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(WHITELISTED_ADDR))
                .andExpect(jsonPath("$.whitelisted").value(true));
    }

    @Test
    @DisplayName("GET /whitelist/check: 未加白地址返回 whitelisted=false")
    void checkWhitelist_nonWhitelistedReturnsFalse() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/whitelist/check").param("address", "0xnonWhitelistedController12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whitelisted").value(false));
    }

    @Test
    @DisplayName("POST /whitelist/add: 加入白名单成功")
    void addWhitelist_success() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/whitelist/add")
                        .param("address", "0xnewControllerWhitelist12345678901")
                        .param("label", "Controller IT")
                        .param("merchantId", "merchant-controller-add"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("0xnewControllerWhitelist12345678901"))
                .andExpect(jsonPath("$.merchantId").value("merchant-controller-add"));
    }

    @Test
    @DisplayName("POST /whitelist/remove: 软删除白名单")
    void removeWhitelist_softDelete() throws Exception {
        // 先添加
        mockMvc.perform(post("/api/v1/wallet/whitelist/add")
                        .param("address", "0xremoveControllerTest12345678901234")
                        .param("merchantId", "merchant-controller-remove"))
                .andExpect(status().isOk());

        // 再移除
        mockMvc.perform(post("/api/v1/wallet/whitelist/remove")
                        .param("address", "0xremoveControllerTest12345678901234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));

        // 验证已移除
        mockMvc.perform(get("/api/v1/wallet/whitelist/check").param("address", "0xremoveControllerTest12345678901234"))
                .andExpect(jsonPath("$.whitelisted").value(false));
    }

    @Test
    @DisplayName("POST /withdrawal/request → /approve → /execute 完整流程")
    void withdrawalFullFlow_requestApproveExecute() throws Exception {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn("0xcontrollerTxHash1234567890abcdef");

        // 1. request
        String requestId = mockMvc.perform(post("/api/v1/wallet/withdrawal/request")
                        .param("to", WHITELISTED_ADDR)
                        .param("amount", "500")
                        .param("currency", "NEX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1");

        // 2. approve
        mockMvc.perform(post("/api/v1/wallet/withdrawal/approve")
                        .param("approvalId", requestId)
                        .param("approverId", "approver-controller-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 3. execute
        mockMvc.perform(post("/api/v1/wallet/withdrawal/execute")
                        .param("approvalId", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.chainTxHash").value("0xcontrollerTxHash1234567890abcdef"));
    }

    @Test
    @DisplayName("GET /custody/balance: 返回 HOT/COLD 余额")
    void custodyBalance_returnsBalances() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/custody/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hot").exists())
                .andExpect(jsonPath("$.cold").exists());
    }
}