package com.vaultri.core.ratelimit.filter;

import com.vaultri.core.ratelimit.config.RateLimitProperties;
import com.vaultri.core.ratelimit.service.RateLimitResult;
import com.vaultri.core.ratelimit.service.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SellerRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Target: POST /api/v1/deals and POST /api/v1/deals/{id}/reminders
        if ("POST".equalsIgnoreCase(method) && isSellerDealsOrRemindersEndpoint(path)) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                try {
                    UUID sellerId = UUID.fromString(jwt.getSubject());
                    RateLimitProperties.RuleConfig rule = properties.getRule("seller-deals", 60, Duration.ofMinutes(1));
                    String key = "rl:seller-deals:" + sellerId;

                    RateLimitResult result = rateLimiter.tryAcquire("seller-deals", key, rule.getLimit(), rule.getWindow().toSeconds());
                    if (!result.allowed()) {
                        response.setStatus(429);
                        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
                        response.setContentType("text/plain;charset=UTF-8");
                        response.getWriter().write("Too many requests. Please retry later.");
                        return;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSellerDealsOrRemindersEndpoint(String path) {
        return path.equals("/api/v1/deals") || path.equals("/deals") ||
               path.matches(".*/deals/[^/]+/reminders.*");
    }
}
