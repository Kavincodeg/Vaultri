package com.vaultri.core.ratelimit.service;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {
    public static RateLimitResult permit() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult reject(long retryAfterSeconds) {
        return new RateLimitResult(false, retryAfterSeconds);
    }
}
