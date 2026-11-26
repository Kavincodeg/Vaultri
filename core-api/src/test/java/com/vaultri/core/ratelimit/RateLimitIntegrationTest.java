package com.vaultri.core.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class RateLimitIntegrationTest extends AbstractIntegrationTest {

    protected static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("vaultri.ratelimit.enabled", () -> "true");
        // Test-specific overrides: quick window for atomic test rule
        registry.add("vaultri.ratelimit.rules.auth-refresh.limit", () -> "10");
        registry.add("vaultri.ratelimit.rules.auth-refresh.window", () -> "2s");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanRedis() {
        Set<String> keys = redisTemplate.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("Rate Limit Atomicity: 50 concurrent threads against limit of 10 allow exactly 10")
    void testAtomicFiftyThreadsAgainstLimitTen() throws Exception {
        int threadCount = 50;
        int limit = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);

        String testIp = "192.168.1.100";
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                                    .with(req -> {
                                        req.setRemoteAddr(testIp);
                                        return req;
                                    })
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"test-token\"}"))
                            .andReturn();

                    int status = result.getResponse().getStatus();
                    if (status != 429) {
                        allowedCount.incrementAndGet();
                    } else {
                        rateLimitedCount.incrementAndGet();
                        assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
                        assertThat(result.getResponse().getContentAsString()).isEqualTo("Too many requests. Please retry later.");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(allowedCount.get()).isEqualTo(limit);
        assertThat(rateLimitedCount.get()).isEqualTo(threadCount - limit);
    }

    @Test
    @DisplayName("Sequential Rate Limiting: exactly N pass, then 429 with Retry-After and fixed body")
    void testSequentialRateLimiting() throws Exception {
        String testIp = "192.168.2.50";

        // Limit for auth-refresh is configured as 10
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .with(req -> {
                                req.setRemoteAddr(testIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"dummy\"}"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }

        // 11th request must receive 429
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(testIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("Too many requests. Please retry later."));
    }

    @Test
    @DisplayName("Window Expiry: requests allowed again after window expires")
    void testWindowExpiryAllowsRequestsAgain() throws Exception {
        String testIp = "192.168.3.10";

        // Exhaust limit of 10
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .with(req -> {
                        req.setRemoteAddr(testIp);
                        return req;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"dummy\"}"));
        }

        // Confirm blocked
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(testIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy\"}"))
                .andExpect(status().isTooManyRequests());

        // Wait for window to expire (configured as 2s in test)
        Thread.sleep(2200);

        // Request allowed again
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(testIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    @DisplayName("Independent Counters: different IPs have independent limits")
    void testIndependentCountersPerIp() throws Exception {
        String ip1 = "10.0.0.1";
        String ip2 = "10.0.0.2";

        // Exhaust ip1
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .with(req -> {
                        req.setRemoteAddr(ip1);
                        return req;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"dummy\"}"));
        }
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(ip1);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy\"}"))
                .andExpect(status().isTooManyRequests());

        // ip2 should still be allowed
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(ip2);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    @DisplayName("Login Per-Email Limit: case-insensitive email shares counter and Redis keys contain no '@'")
    void testLoginPerEmailLimitCaseInsensitiveAndNoAtInKey() throws Exception {
        String email = "CaseTest_" + UUID.randomUUID() + "@Vaultri.com";
        String clientIp = "172.16.0.5";

        // Default login per-email limit is 5 per 15 minutes
        for (int i = 0; i < 5; i++) {
            String testEmail = (i % 2 == 0) ? email.toLowerCase() : email.toUpperCase();
            mockMvc.perform(post("/api/v1/auth/login")
                    .with(req -> {
                        req.setRemoteAddr(clientIp);
                        return req;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(testEmail, "WrongPassword!"))));
        }

        // 6th attempt with mixed case should be rate limited by email rule
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "WrongPassword!"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("Too many requests. Please retry later."));

        // Scan Redis keys to verify NO raw email or '@' symbol exists in any key
        Set<String> keys = redisTemplate.keys("rl:*");
        assertThat(keys).isNotEmpty();
        for (String k : keys) {
            assertThat(k).doesNotContain("@");
            assertThat(k).doesNotContainIgnoringCase("Vaultri.com");
        }
    }

    @Test
    @DisplayName("Spoofed X-Forwarded-For with trusted-proxy-count 0 is ignored")
    void testSpoofedXForwardedForIgnoredWhenTrustedProxyZero() throws Exception {
        String realIp = "192.168.5.1";

        // Send 10 requests with realIp but differing spoofed X-Forwarded-For headers
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .with(req -> {
                        req.setRemoteAddr(realIp);
                        return req;
                    })
                    .header("X-Forwarded-For", "spoofed.ip." + i + ", 1.2.3.4")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"dummy\"}"));
        }

        // 11th request from same realIp with another spoofed header must still be 429
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(realIp);
                            return req;
                        })
                        .header("X-Forwarded-For", "completely.new.spoofed.ip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Seller Rate Limit: authenticated seller limit on deals and manual reminder")
    void testSellerRateLimitOnDealsAndReminders() throws Exception {
        String sellerEmail = "seller_rl_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(sellerEmail, "Password123!", "Seller RL", null))))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(sellerEmail, "Password123!"))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();

        // Perform requests under seller limit
        CreateDealRequest dealReq = new CreateDealRequest(
                "Customer", "cust@test.com", null, "RL Deal", 100000L, 20, Instant.now().plusSeconds(86400), "Terms"
        );

        MvcResult dealRes = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dealReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID dealId = UUID.fromString(objectMapper.readTree(dealRes.getResponse().getContentAsString()).path("id").asText());

        // Manual reminder endpoint uses same seller-deals limit
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }
}
