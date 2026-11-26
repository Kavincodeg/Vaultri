package com.vaultri.core.report.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.report.config.ReportCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReportCacheProperties properties;

    public <T> T getOrCompute(String endpoint, UUID sellerId, String normalizedFrom, String normalizedTo,
                              Class<T> clazz, JavaType javaType, Supplier<T> dbFallback) {
        if (!properties.isEnabled()) {
            return dbFallback.get();
        }

        String cacheKey = String.format("cache:report:%s:%s:%s:%s", endpoint, sellerId, normalizedFrom, normalizedTo);

        // Read from cache
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                if (javaType != null) {
                    return objectMapper.readValue(cachedJson, javaType);
                } else {
                    return objectMapper.readValue(cachedJson, clazz);
                }
            }
        } catch (Exception e) {
            // Fail open: log only class name, fall through to database
            log.error("Report cache read failure: {}", e.getClass().getSimpleName());
        }

        // Database fallback
        T result = dbFallback.get();

        // Write to cache
        try {
            String json = objectMapper.writeValueAsString(result);
            Duration ttl = properties.getTtl() != null ? properties.getTtl() : Duration.ofSeconds(60);
            redisTemplate.opsForValue().set(cacheKey, json, ttl);
        } catch (Exception e) {
            // Fail open: log only class name, never fail request
            log.error("Report cache write failure: {}", e.getClass().getSimpleName());
        }

        return result;
    }
}
