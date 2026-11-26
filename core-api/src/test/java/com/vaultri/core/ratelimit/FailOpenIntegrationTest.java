package com.vaultri.core.ratelimit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class FailOpenIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void configureUnreachableRedis(DynamicPropertyRegistry registry) {
        // Point to an unreachable port to simulate Redis being down
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "65432");
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
        registry.add("vaultri.ratelimit.enabled", () -> "true");
        registry.add("vaultri.cache.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger rootLogger;

    @BeforeEach
    void setupLogAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDownLogAppender() {
        if (rootLogger != null && listAppender != null) {
            rootLogger.detachAppender(listAppender);
        }
    }

    @Test
    @DisplayName("Fail Open: when Redis is unreachable, rate limiter allows request and logs contain no IP or email")
    void testRateLimiterFailsOpenWhenRedisUnreachable() throws Exception {
        String testIp = "203.0.113.195";
        String testEmail = "fail_open_" + UUID.randomUUID() + "@vaultri.com";

        // Call login with unreachable Redis: request must NOT fail with 500 or 429
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(testIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(testEmail, "WrongPassword!"))))
                .andReturn();

        // Expect normal application response (401 BadCredentialsException for wrong credentials, not rate limited or error)
        assertThat(result.getResponse().getStatus()).isEqualTo(401);

        // Verify that NO log entry contains the client IP or email address
        for (ILoggingEvent event : listAppender.list) {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain(testIp);
            assertThat(message).doesNotContain(testEmail);
        }
    }

    @Test
    @DisplayName("Report Cache Fail Open: when Redis is unreachable, falls through to database and succeeds")
    void testReportCacheFailsOpenWhenRedisUnreachable() throws Exception {
        String email = "cache_fail_open_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupRequest(email, "Password123!", "Seller", null))))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Password123!"))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();

        // Report request must succeed by falling through to database
        MvcResult res = mockMvc.perform(get("/api/v1/reports/summary")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        // Verify logs do not leak sensitive information
        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).doesNotContain(email);
        }
    }
}
