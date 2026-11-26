package com.vaultri.core.ratelimit.service;

import com.vaultri.core.ratelimit.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiter {

    public static final String LUA_SCRIPT =
            "local current = redis.call('INCR', KEYS[1])\n" +
            "if current == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
            "end\n" +
            "local ttl = redis.call('TTL', KEYS[1])\n" +
            "if ttl < 0 then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
            "    ttl = tonumber(ARGV[2])\n" +
            "end\n" +
            "local limit = tonumber(ARGV[1])\n" +
            "if current > limit then\n" +
            "    return {0, ttl}\n" +
            "else\n" +
            "    return {1, ttl}\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final RedisScript<List> redisScript = new DefaultRedisScript<>(LUA_SCRIPT, List.class);

    /**
     * Attempts to acquire a rate limit permit.
     *
     * Decision: If Redis is unavailable or times out, fail open (allow the request).
     * Log one line with only the exception class name and the rule name, and never let
     * the exception reach the caller. Rate limiting must not take the API down.
     */
    public RateLimitResult tryAcquire(String ruleName, String key, int limit, long windowSeconds) {
        if (!properties.isEnabled()) {
            return RateLimitResult.permit();
        }

        try {
            List<Long> result = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            if (result != null && !result.isEmpty()) {
                boolean allowed = result.get(0) != null && result.get(0) == 1L;
                long ttl = (result.size() > 1 && result.get(1) != null) ? Math.max(1L, result.get(1)) : windowSeconds;
                return new RateLimitResult(allowed, ttl);
            }
            return RateLimitResult.permit();
        } catch (Exception e) {
            // Fail open: log one line with only the exception class name and the rule name
            log.error("Rate limiter Redis failure: rule={}, exception={}", ruleName, e.getClass().getSimpleName());
            return RateLimitResult.permit();
        }
    }
}
