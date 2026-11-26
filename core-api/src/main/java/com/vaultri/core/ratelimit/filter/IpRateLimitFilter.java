package com.vaultri.core.ratelimit.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.common.util.HashUtil;
import com.vaultri.core.ratelimit.config.RateLimitProperties;
import com.vaultri.core.ratelimit.service.ClientIpResolver;
import com.vaultri.core.ratelimit.service.RateLimitResult;
import com.vaultri.core.ratelimit.service.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method)) {
            if (path.endsWith("/auth/login")) {
                handleLogin(request, response, filterChain);
                return;
            } else if (path.endsWith("/auth/signup")) {
                handleSignup(request, response, filterChain);
                return;
            } else if (path.endsWith("/auth/refresh")) {
                handleRefresh(request, response, filterChain);
                return;
            } else if (path.equals("/webhooks/razorpay") || path.endsWith("/webhooks/razorpay")) {
                handleWebhook(request, response, filterChain);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void handleLogin(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = clientIpResolver.resolveClientIp(request);

        // 1. Check IP limit (default 10 per minute)
        RateLimitProperties.RuleConfig ipRule = properties.getRule("auth-login-ip", 10, Duration.ofMinutes(1));
        String ipKey = "rl:auth-login-ip:" + clientIp;
        RateLimitResult ipResult = rateLimiter.tryAcquire("auth-login-ip", ipKey, ipRule.getLimit(), ipRule.getWindow().toSeconds());
        if (!ipResult.allowed()) {
            rejectTooManyRequests(response, ipResult.retryAfterSeconds());
            return;
        }

        // 2. Wrap request to read email without consuming stream for downstream controllers
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        String email = extractEmail(wrappedRequest.getCachedBody());

        if (email != null && !email.isBlank()) {
            String emailHash = HashUtil.hashEmail(email);
            RateLimitProperties.RuleConfig emailRule = properties.getRule("auth-login-email", 5, Duration.ofMinutes(15));
            String emailKey = "rl:auth-login-email:" + emailHash;
            RateLimitResult emailResult = rateLimiter.tryAcquire("auth-login-email", emailKey, emailRule.getLimit(), emailRule.getWindow().toSeconds());
            if (!emailResult.allowed()) {
                rejectTooManyRequests(response, emailResult.retryAfterSeconds());
                return;
            }
        }

        filterChain.doFilter(wrappedRequest, response);
    }

    private void handleSignup(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = clientIpResolver.resolveClientIp(request);
        RateLimitProperties.RuleConfig rule = properties.getRule("auth-signup", 5, Duration.ofHours(1));
        String key = "rl:auth-signup:" + clientIp;
        RateLimitResult result = rateLimiter.tryAcquire("auth-signup", key, rule.getLimit(), rule.getWindow().toSeconds());
        if (!result.allowed()) {
            rejectTooManyRequests(response, result.retryAfterSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void handleRefresh(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = clientIpResolver.resolveClientIp(request);
        RateLimitProperties.RuleConfig rule = properties.getRule("auth-refresh", 30, Duration.ofMinutes(1));
        String key = "rl:auth-refresh:" + clientIp;
        RateLimitResult result = rateLimiter.tryAcquire("auth-refresh", key, rule.getLimit(), rule.getWindow().toSeconds());
        if (!result.allowed()) {
            rejectTooManyRequests(response, result.retryAfterSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void handleWebhook(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = clientIpResolver.resolveClientIp(request);
        RateLimitProperties.RuleConfig rule = properties.getRule("webhook", 600, Duration.ofMinutes(1));
        String key = "rl:webhook:" + clientIp;
        RateLimitResult result = rateLimiter.tryAcquire("webhook", key, rule.getLimit(), rule.getWindow().toSeconds());
        if (!result.allowed()) {
            rejectTooManyRequests(response, result.retryAfterSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String extractEmail(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode emailNode = node.path("email");
            if (!emailNode.isMissingNode() && !emailNode.isNull()) {
                return emailNode.asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void rejectTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Too many requests. Please retry later.");
    }
}
