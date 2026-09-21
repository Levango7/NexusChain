package org.nexus.gateway.repository;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现测试（2026-09-14 kind 演练）：register → generateApiKey 后的
 * findByActiveApiKey 在 k8s 部署态 H2 下返回空（40101 Invalid API key）。
 * 本地 @SpringBootTest + H2 判定：通过 = k8s 环境差异；失败 = 实体/查询缺陷。
 */
// 2026-09-21 修复：原为 @ActiveProfiles("test")，而 "test" 档位**不提供**
// IdempotencyStore（内存实现只覆盖 dev/sandbox）与 KeyManager
// （LocalFileKeyManager 为 dev/prod、SandboxKeyManager 为 sandbox、VaultKeyManager 为 prod），
// 导致上下文启动即失败 —— 该测试从未跑通过。
// 本项目既有约定是集成测试叠加 "sandbox" 档位（PaymentE2EIntegrationTest 亦为
// {"test","sandbox"}），此处对齐该约定：test 提供 H2 数据源，sandbox 提供所需 bean。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "sandbox"})
@Transactional
class MerchantApiKeyLookupTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantService merchantService;

    @MockitoBean
    private RateLimiter rateLimiter;

    // 2026-09-21 修复：PaymentServiceImpl 等构造函数需要 Tracer bean，
    // 而 gateway 的 NoopTracerConfig 仅覆盖 "dev" 档位（Spring Boot 4.0 将
    // tracing autoconfig 移出核心，故测试环境无自动装配的 Tracer）。
    // 本项目其他 @SpringBootTest 集成测试一律以 @MockitoBean 提供该依赖
    // （V2ApiIntegrationTest / GatewayCoreIntegrationTest / PaymentE2EIntegrationTest 等），
    // 此处对齐同一做法。
    @MockitoBean
    private Tracer tracer;

    @Test
    void generatedApiKeyIsFindable() {
        Merchant merchant = merchantService.register("lookup-test", "lookup@invalid", "0x0");
        MerchantService.ApiKeyPair pair = merchantService.generateApiKey(merchant.getId());

        Optional<Merchant> found = merchantRepository.findByActiveApiKey(pair.getApiKey());

        assertThat(found).as("findByActiveApiKey 必须能查到刚生成的 key")
                .isPresent()
                .get()
                .extracting(Merchant::getId)
                .isEqualTo(merchant.getId());
    }
}