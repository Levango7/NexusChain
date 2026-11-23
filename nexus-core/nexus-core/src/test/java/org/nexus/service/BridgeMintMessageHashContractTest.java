package org.nexus.service;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MCP 签发通道契约测试（B 线）。
 *
 * <p>覆盖三项链下可验证的契约：
 * <ol>
 *   <li>messageHash 跨语言同构：以独立 Keccak 实现（Python pycryptodome）算出的
 *       固定向量校验 Java {@link BridgeService#computeMintMessageHash}；</li>
 *   <li>阈值动态读取：{@link BridgeService#getLimit()} 暴露 minValidators，
 *       且少于阈值的验签数在 5010 未启用响应之前先 fail-closed；</li>
 *   <li>上报幂等键：MCP 侧 {@code (type, messageHash, timestamp)} 规范化键的
 *       一致性、大小写归一、时间戳区分与非法输入拒绝。</li>
 * </ol>
 *
 * <p>本测试不修改任何生产行为，也不触碰链上门禁；仅固化签发通道的对接契约。</p>
 */
class BridgeMintMessageHashContractTest {

    @Test
    void goldenVectorsMatchIndependentKeccakImplementation() {
        assertHash("20cefb336a17d35342a312f0f047ed7cadc875f31ede9965d775cad2e9fb733b",
                "br-1", "ETH", "0xabc", 1000L, 1700000000L);
        assertHash("dc525bdc1446df7794166a382015de3c0b90bed46bd245e82e6be9f22b1b979f",
                "br-2", "", "recipient", 0L, 0L);
        assertHash("f442372a63a712e8b687a959c6cf09f9e0cbbe3478afef90faf7cb8149053684",
                "bridge-xyz", "BSC", "0xDEADBEEF", 123456789L, 1999999999L);
    }

    @Test
    void nullSourceChainIsSameAsEmptyString() {
        assertEquals(
                Hex.encodeHexString(BridgeService.computeMintMessageHash("br-1", "", "r", 1L, 2L)),
                Hex.encodeHexString(BridgeService.computeMintMessageHash("br-1", null, "r", 1L, 2L)));
    }

    @Test
    void domainAndFieldBoundariesAreSeparated() {
        String a = Hex.encodeHexString(BridgeService.computeMintMessageHash("a", "b", "r", 1L, 2L));
        String b = Hex.encodeHexString(BridgeService.computeMintMessageHash("a\u0000b", "", "r", 1L, 2L));
        assertNotEquals(a, b);
    }

    @Test
    void thresholdIsReadableFromGetLimitAndEnforcedBeforeNotEnabled() throws Exception {
        BridgeService service = new BridgeService();
        setField(service, "minValidators", 3);

        APIResult limit = service.getLimit();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) limit.getData();
        assertEquals(3, ((Number) data.get("minValidators")).intValue());

        // 2 个签名 < minValidators=3：先 5000 fail-closed，而非 5010 未启用。
        APIResult result = service.mint("br-1", "ETH", "recipient", 10L,
                List.of("pk"), List.of("sig", "sig"));
        assertEquals(APIResult.FAIL, result.getCode());
        assertEquals("Not enough validator signatures: 2 < 3", result.getMessage());
    }

    @Test
    void signReportKeyIsCanonicalAndIdempotent() {
        String hash = "20cefb336a17d35342a312f0f047ed7cadc875f31ede9965d775cad2e9fb733b";
        SignReportKey k1 = SignReportKey.of("SIGN", hash, 1700000000L);
        SignReportKey k2 = SignReportKey.of("SIGN", hash.toUpperCase(), 1700000000L);
        assertEquals(k1, k2);
        assertEquals("SIGN|" + hash + "|1700000000", k1.asString());

        assertNotEquals(k1, SignReportKey.of("SIGN", hash, 1700000001L));
        assertNotEquals(k1, SignReportKey.of("VERIFY", hash, 1700000000L));
        assertThrows(IllegalArgumentException.class, () -> SignReportKey.of("SIGN", "abc", 1L));
        assertThrows(IllegalArgumentException.class, () -> SignReportKey.of("", hash, 1L));
        assertThrows(IllegalArgumentException.class, () -> SignReportKey.of("SIGN", hash, 0L));
    }

    private static void assertHash(String expected, String bridgeTxId, String sourceChain,
                                   String recipient, long amount, long timelockExpiry) {
        byte[] actual = BridgeService.computeMintMessageHash(
                bridgeTxId, sourceChain, recipient, amount, timelockExpiry);
        assertEquals(expected, Hex.encodeHexString(actual));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * MCP 侧上报动作幂等键的参考实现（仅测试范围，不引入生产代码）。
     *
     * <p>规范：{@code type | lowercase(messageHash 64 hex) | timestamp}，
     * 其中 timestamp 必须为正整数。消费端按 messageHash 去重，与链上
     * {@code BridgeMintReplayGuard} 的幂等口径一致。</p>
     */
    static final class SignReportKey {
        private final String type;
        private final String messageHash;
        private final long timestamp;

        private SignReportKey(String type, String messageHash, long timestamp) {
            this.type = type;
            this.messageHash = messageHash;
            this.timestamp = timestamp;
        }

        static SignReportKey of(String type, String messageHash, long timestamp) {
            if (type == null || type.isEmpty()) {
                throw new IllegalArgumentException("type is required");
            }
            if (messageHash == null || !messageHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("messageHash must be 64 hex chars");
            }
            if (timestamp <= 0) {
                throw new IllegalArgumentException("timestamp must be positive");
            }
            return new SignReportKey(type, messageHash.toLowerCase(), timestamp);
        }

        String asString() {
            return type + "|" + messageHash + "|" + timestamp;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SignReportKey)) {
                return false;
            }
            SignReportKey that = (SignReportKey) other;
            return type.equals(that.type) && messageHash.equals(that.messageHash)
                    && timestamp == that.timestamp;
        }

        @Override
        public int hashCode() {
            return asString().hashCode();
        }
    }
}
