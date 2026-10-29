package org.nexus.walletsvc.fallback;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.SigningResponses;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A2 §7.5 决策一（2026-10-29，P0-2 配套）降级路径单元测试。
 *
 * <p>背景：signing 以 HTTP 非 2xx 携带 JSON 业务信封返回失败（BIZ_FAIL→500 /
 * BIZ_BAD_REQUEST→400 / BIZ_FORBIDDEN→403），而本模块
 * {@code spring.cloud.openfeign.circuitbreaker.enabled=true} 会把该 FeignException 转给
 * fallback。原实现一律返回 null，使「明确未动账（5000/5001/4000/4030）」与
 * 「凭据已消费（5003）」塌缩成同一个「结果不明」。修复后 fallback 把<b>带已定义
 * statusCode</b> 的信封透传给调用方，使降级路径与异常路径收敛到同一三态语义。</p>
 *
 * <p>钉死的不变式：</p>
 * <ol>
 *   <li>异常体带已定义码（含 5003）⇒ 签名方法返回该信封（由 classify 侧决定 FAILED/UNVERIFIED）；</li>
 *   <li>异常体不可解析 / 未知码 / 熔断（非 Feign 异常）⇒ 签名方法返回 null（结果不明）；</li>
 *   <li>登记方法无条件返回 null（未登记 = 可安全回落既有审批流，语义不变）。</li>
 * </ol>
 */
class WalletSvcSigningServiceFallbackFactoryTest {

    private static final Map<String, Collection<String>> NO_HEADERS = Collections.emptyMap();
    private static final String FROM_PUBKEY = "02abababababababababababababababababababababababababababababababab";
    private static final String TO_PUBKEY_HASH = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    private static final BigDecimal AMOUNT = new BigDecimal("50000");

    private static Request postRequest() {
        return Request.create(Request.HttpMethod.POST,
                "http://signing-service/api/v1/transfers/sign",
                NO_HEADERS, new byte[0], StandardCharsets.UTF_8);
    }

    /** 构造携带 JSON 业务信封的 FeignException（serverError=true → HTTP 500，false → HTTP 400）。 */
    private static FeignException feignFailure(boolean serverError, String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        return serverError
                ? new FeignException.InternalServerError(
                        "signing-service returned 500", postRequest(), content, NO_HEADERS)
                : new FeignException.BadRequest(
                        "signing-service returned 400", postRequest(), content, NO_HEADERS);
    }

    @Test
    @DisplayName("响应体=5000（明确未动账，HTTP 500）→ 透传信封，classify 侧可判 FAILED")
    void feign5000Body_envelopePassedThrough() {
        SigningServiceFeignClient client = new WalletSvcSigningServiceFallbackFactory().create(
                feignFailure(true,
                        "{\"statusCode\":5000,\"message\":\"No signing key available\",\"data\":\"\"}"));

        Map<String, Object> legacy = client.signTransfer(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);
        assertNotNull(legacy, "明确未动账应透传信封，而不是塌缩成 null");
        assertEquals(5000, SigningResponses.statusCode(legacy));

        Map<String, Object> approval = client.signTransferWithBusinessApproval(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, "BA-1");
        assertNotNull(approval);
        assertEquals(5000, SigningResponses.statusCode(approval));
    }

    @Test
    @DisplayName("响应体=4000（BIZ_BAD_REQUEST，HTTP 400）→ 透传信封")
    void feign4000Body_envelopePassedThrough() {
        SigningServiceFeignClient client = new WalletSvcSigningServiceFallbackFactory().create(
                feignFailure(false,
                        "{\"statusCode\":4000,\"message\":\"toPubkeyHash must be 40-hex\",\"data\":\"\"}"));

        Map<String, Object> legacy = client.signTransfer(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);
        assertNotNull(legacy);
        assertEquals(4000, SigningResponses.statusCode(legacy));
    }

    @Test
    @DisplayName("响应体=5003（凭据已消费，结果不明）→ 透传信封，A2 腿判 UNVERIFIED")
    void feign5003Body_envelopePassedThrough() {
        SigningServiceFeignClient client = new WalletSvcSigningServiceFallbackFactory().create(
                feignFailure(true,
                        "{\"statusCode\":5003,\"message\":\"business approval already consumed\",\"data\":\"\"}"));

        Map<String, Object> approval = client.signTransferWithBusinessApproval(
                FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, "BA-1");
        assertNotNull(approval, "5003 是已定义码：透传信封让 classify 侧按 UNVERIFIED 处理（信息不丢失）");
        assertEquals(5003, SigningResponses.statusCode(approval));
    }

    @Test
    @DisplayName("响应体不可解析 / 未知码 / 非 Feign 异常（熔断打开）⇒ 返回 null（fail-closed）")
    void unparseableOrUnknownOrNonFeign_returnsNull() {
        WalletSvcSigningServiceFallbackFactory factory = new WalletSvcSigningServiceFallbackFactory();

        // 1. 非 JSON 响应体（503 网关错误页等）
        SigningServiceFeignClient garbage = factory.create(feignFailure(true, "<html>503 Service Unavailable</html>"));
        assertNull(garbage.signTransfer(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT));
        assertNull(garbage.signTransferWithBusinessApproval(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, "BA-1"));

        // 2. JSON 信封但 statusCode 未知（契约未确立 ⇒ 不能假定未动账）
        SigningServiceFeignClient unknown = factory.create(
                feignFailure(true, "{\"statusCode\":5999,\"message\":\"???\",\"data\":\"\"}"));
        assertNull(unknown.signTransfer(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT));
        assertNull(unknown.signTransferWithBusinessApproval(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, "BA-1"));

        // 3. 非 Feign 异常（熔断器打开、调用超时等，无响应体）
        SigningServiceFeignClient nonFeign = factory.create(new RuntimeException("circuit breaker open"));
        assertNull(nonFeign.signTransfer(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT));
        assertNull(nonFeign.signTransferWithBusinessApproval(FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, "BA-1"));
    }

    @Test
    @DisplayName("登记降级语义不变：即使异常体带 statusCode 也返回 null（未登记可安全回落）")
    void registerBusinessApproval_alwaysNullEvenWithEnvelopeBody() {
        SigningServiceFeignClient client = new WalletSvcSigningServiceFallbackFactory().create(
                feignFailure(true, "{\"statusCode\":5000,\"message\":\"fail\",\"data\":\"\"}"));

        assertNull(client.registerBusinessApproval(
                "BA-1", "hash", AMOUNT, "NEX", FROM_PUBKEY, TO_PUBKEY_HASH, null, null));
    }
}
