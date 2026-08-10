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
 * Binance 行情数据源适配器。
 *
 * <p>通过 Binance REST API（{@code /api/v3/ticker/price}）拉取最新成交价。
 * 交易对约定为 {@code <ASSET>USDT}。支持通过 {@link #setStaticPrice} 注入
 * 静态价格（离线 / 测试场景优先于 HTTP 拉取）。
 */
@Slf4j
@Component
public class BinancePriceFeed implements PriceFeed {

    /** Binance 行情接口 */
    private static final String API_URL = "https://api.binance.com/api/v3/ticker/price?symbol=%sUSDT";

    /** 请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 从响应 JSON 中提取 price 字段 */
    private static final Pattern PRICE_PATTERN = Pattern.compile("\"price\"\\s*:\\s*\"([0-9.]+)\"");

    /** 静态价格注入表（asset → price），测试 / 离线用 */
    private final Map<String, BigDecimal> staticPrices = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public String sourceName() {
        return "BINANCE";
    }

    @Override
    public BigDecimal fetch(String asset) {
        if (asset == null || asset.isBlank()) {
            return null;
        }
        BigDecimal staticPrice = staticPrices.get(asset.toUpperCase(Locale.ROOT));
        if (staticPrice != null) {
            return staticPrice;
        }
        try {
            String url = String.format(API_URL, asset.toUpperCase(Locale.ROOT));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Binance fetch non-200: asset={}, status={}", asset, response.statusCode());
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
            log.debug("Binance fetch failed: asset={}, error={}", asset, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        // 有静态价格注入时视为可用；否则探测接口可达性
        if (!staticPrices.isEmpty()) {
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.binance.com/api/v3/ping"))
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
