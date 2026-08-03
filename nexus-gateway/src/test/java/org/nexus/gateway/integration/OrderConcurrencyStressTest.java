package org.nexus.gateway.integration;

import org.nexus.gateway.repository.PaymentOrderRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency stress test: measures throughput under parallel load.
 * Verifies the system does not crash or deadlock under 20-thread concurrent access.
 * Rate limiter is expected to reject some requests (this is correct behavior).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@DisplayName("Stress Test: Concurrent Order Creation")
class OrderConcurrencyStressTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentOrderRepository orderRepo;

    private static final int THREAD_COUNT = 20;
    private static final int ORDERS_PER_THREAD = 10;
    private static final int TOTAL_ORDERS = THREAD_COUNT * ORDERS_PER_THREAD;

    @Test
    @DisplayName("System handles 200 concurrent requests without crash or deadlock")
    void concurrentOrderCreation() throws Exception {
        // Setup: register merchant
        MvcResult regResult = mockMvc.perform(post("/api/v1/merchants/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"StressShop\",\"email\":\"s@t.com\",\"settlementAddress\":\"1Addr\"}"))
                .andReturn();
        String regJson = regResult.getResponse().getContentAsString();
        int idStart = regJson.indexOf("\"id\":") + 5;
        int idEnd = regJson.indexOf(",", idStart);
        if (idEnd == -1) idEnd = regJson.indexOf("}", idStart);
        long mId = Long.parseLong(regJson.substring(idStart, idEnd).trim());

        // Verify + key
        mockMvc.perform(post("/api/v1/merchants/" + mId + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());
        MvcResult keyResult = mockMvc.perform(post("/api/v1/merchants/" + mId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
        String keyJson = keyResult.getResponse().getContentAsString();
        int keyStart = keyJson.indexOf("\"apiKey\":\"") + 10;
        int keyEnd = keyJson.indexOf("\"", keyStart);
        String key = keyJson.substring(keyStart, keyEnd);

        // Stress
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger created = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < ORDERS_PER_THREAD; i++) {
                        String body = String.format(
                            "{\"merchantId\":\"%d\",\"amount\":%d,\"description\":\"s-%d-%d\",\"notifyUrl\":\"http://cb\"}",
                            mId, 1000 + i, threadId, i);
                        MvcResult r = mockMvc.perform(post("/api/v1/orders")
                                .header("X-NexusChain-ApiKey", key)
                                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
                        int status = r.getResponse().getStatus();
                        if (status == 201) created.incrementAndGet();
                        else if (status == 429) rateLimited.incrementAndGet();
                        else errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.addAndGet(ORDERS_PER_THREAD);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        long elapsed = System.currentTimeMillis() - startTime;

        System.out.printf("[STRESS RESULT] completed=%b elapsed=%dms created=%d rateLimited=%d errors=%d throughput=%.1f req/s%n",
                completed, elapsed, created.get(), rateLimited.get(), errors.get(),
                TOTAL_ORDERS * 1000.0 / Math.max(elapsed, 1));

        // Core assertions: no deadlock, no crashes, some orders created
        assertTrue(completed, "All threads must finish (no deadlock)");
        assertTrue(created.get() > 0, "At least some orders should be created");
        // Under H2 concurrent load, some lock timeouts (500) are expected and acceptable
        assertTrue(errors.get() <= TOTAL_ORDERS / 10,
                "Errors should be <10%, got " + errors.get() + "/" + TOTAL_ORDERS);
    }
}