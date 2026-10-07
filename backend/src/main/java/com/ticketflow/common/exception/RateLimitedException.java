package com.ticketflow.common.exception;

public class RateLimitedException extends BusinessException {
    private final long retryAfterSeconds;
    public RateLimitedException(long retryAfterSeconds) {
        super(429, "TOO_MANY_REQUESTS", "请求过于频繁，请稍后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
