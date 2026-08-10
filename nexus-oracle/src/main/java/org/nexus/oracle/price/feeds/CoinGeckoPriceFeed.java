package org.nexus.oracle.price.feeds;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.price.PriceFeed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CoinGecko 聚合行情数据源适配器。
 *
 * <p>通过 CoinGecko API（{@code /api/v3/simple/price}）拉取多市场聚合价格。
 * 资产符号先映射为 CoinGecko 的 asset id（内置 BTC/ETH 常见映射，未命中时
 * 按小写符号尝试）。支持通过 {@link #setStaticPrice} 注入静态价格。
 */
@Slf4j
@Component
public class CoinGeckoPriceFeed implements PriceFeed {

    /** CoinGecko 简单价格接口 */
    private static final String API_URL = "https://api.coingecko.com/api/v3/simple/price?ids=%s&vs_currencies=usd";

    /** 请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 资产符号 → CoinGecko asset id 映射 */
    private static final Map<String, String> ASSET_ID_MAP = Map.of(
            "BTC", "bitcoin",
            "ETH", "ethereum",
            "USDT", "tether",
            "BNB", "binancecoin",
            "SOL", "solana");

    /** 从响应 JSON 中提取 usd 价格 */
    private static final Pattern PRICE_PATTERN = Pattern.compile("\"usd\"\\s*:\\s*([0-9.]+)");

    /** 静态价格注入表（asset → price），测试 / 离线用 */
    private final Map<String, BigDecimal> staticPrices = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public String sourceName() {
        return "COINGECKO";
    }

    @Override
    public BigDecimal fetch(String asset) {
        if (asset == null || asset.isBlank()) {
            return null;
        }
        String symbol = asset.toUpperCase(Locale.ROOT);
        BigDecimal staticPrice = staticPrices.get(symbol);
        if (staticPrice != null) {
            return staticPrice;
        }
        try {
            String assetId = ASSET_ID_MAP.getOrDefault(symbol, symbol.toLowerCase(Locale.ROOT));
            String url = String.format(API_URL, assetId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("CoinGecko fetch non-200: asset={}, status={}", asset, response.statusCode());
                return null;
            }
            Matcher matcher = PRICE_PATTERN.matcher(response.body());
            if (matcher.find()) {
                return new BigDecimal(matcher.group(1));
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("CoinGecko fetch failed: asset={}, error={}", asset, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        if (!staticPrices.isEmpty()) {
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.coingecko.com/api/v3/ping"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 注入静态价格（测试 / 离线场景）。
     *
     * @param asset 资产符号
     * @param price 价格
     */
    public void setStaticPrice(String asset, BigDecimal price) {
        if (asset != null && price != null) {
            staticPrices.put(asset.toUpperCase(Locale.ROOT), price);
        }
    }

    /** 清空静态价格。 */
    public void clearStaticPrices() {
        staticPrices.clear();
    }
}
