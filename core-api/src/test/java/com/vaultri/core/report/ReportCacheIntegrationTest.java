package com.vaultri.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.report.service.ReportingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ReportCacheIntegrationTest extends AbstractIntegrationTest {

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
        registry.add("vaultri.cache.enabled", () -> "true");
        registry.add("vaultri.cache.ttl", () -> "2s"); // short TTL for test
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @SpyBean
    private ReportingService reportingService;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        Set<String> keys = redisTemplate.keys("cache:report:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        String emailA = "seller_cache_a_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailA, "Password123!", "Seller A", null))))
                .andExpect(status().isCreated());
        MvcResult loginA = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(emailA, "Password123!"))))
                .andExpect(status().isOk())
                .andReturn();
        tokenA = objectMapper.readTree(loginA.getResponse().getContentAsString()).path("accessToken").asText();

        String emailB = "seller_cache_b_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailB, "Password123!", "Seller B", null))))
                .andExpect(status().isCreated());
        MvcResult loginB = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(emailB, "Password123!"))))
                .andExpect(status().isOk())
                .andReturn();
        tokenB = objectMapper.readTree(loginB.getResponse().getContentAsString()).path("accessToken").asText();

        // Seed a deal for seller A
        CreateDealRequest dealReq = new CreateDealRequest(
                "Customer", "cust@test.com", null, "Cache Deal", 100000L, 20, Instant.now().plusSeconds(86400), "Terms"
        );
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dealReq)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Report Cache Hit: second identical report request hits Redis, not database service")
    void testSecondIdenticalReportRequestHitsCache() throws Exception {
        // Request 1: cache miss, invokes reportingService
        MvcResult res1 = mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andReturn();

        verify(reportingService, times(1)).getSummary(any(), any(), any());

        // Request 2: identical parameters, should hit cache
        MvcResult res2 = mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andReturn();

        // Verification: reportingService was NOT called again (still exactly 1 invocation)
        verify(reportingService, times(1)).getSummary(any(), any(), any());

        // Both responses match
        assertThat(res1.getResponse().getContentAsString()).isEqualTo(res2.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Report Cache Isolation: different seller never receives another seller's cached response")
    void testDifferentSellerIsolationInCache() throws Exception {
        // Seller A requests summary
        mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk());

        // Seller B requests same range -> must invoke reportingService for Seller B
        mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + tokenB)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk());

        verify(reportingService, times(2)).getSummary(any(), any(), any());
    }

    @Test
    @DisplayName("Report Cache Range Isolation: different date ranges do not share an entry")
    void testDifferentDateRangesDoNotShareEntry() throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("from", "2026-01-01")
                        .param("to", "2026-06-30"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("from", "2026-07-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk());

        verify(reportingService, times(2)).getSummary(any(), any(), any());
    }

    @Test
    @DisplayName("Report Cache TTL Expiry: entry expires after TTL and invokes database service again")
    void testCacheEntryExpiresAfterTtl() throws Exception {
        mockMvc.perform(get("/api/v1/reports/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        verify(reportingService, times(1)).getReminders(any());

        // Immediate second request: cached
        mockMvc.perform(get("/api/v1/reports/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        verify(reportingService, times(1)).getReminders(any());

        // Wait for TTL (2 seconds)
        Thread.sleep(2200);

        // Next request after expiry: invokes reportingService again
        mockMvc.perform(get("/api/v1/reports/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        verify(reportingService, times(2)).getReminders(any());
    }
}
