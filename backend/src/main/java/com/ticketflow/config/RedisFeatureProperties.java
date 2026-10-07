package com.ticketflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ticketflow.redis")
public record RedisFeatureProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("tf:dev:v1") String namespace,
        @DefaultValue("30") int cacheTtlSeconds,
        @DefaultValue("3") int emptyTtlSeconds,
        @DefaultValue("8") int queryConcurrency,
        @DefaultValue("500") int mergeWaitMillis,
        @DefaultValue("10") int userLimit,
        @DefaultValue("50") int sessionLimit,
        @DefaultValue("1000") int windowMillis) {
    public RedisFeatureProperties {
        if (namespace == null || !namespace.matches("tf:(dev|test|demo):[A-Za-z0-9_-]{1,64}")
                || cacheTtlSeconds < 1 || cacheTtlSeconds > 300
                || emptyTtlSeconds < 1 || emptyTtlSeconds > cacheTtlSeconds
                || queryConcurrency < 1 || queryConcurrency > 100
                || mergeWaitMillis < 1 || mergeWaitMillis > 3000
                || userLimit < 1 || sessionLimit < 1
                || windowMillis < 100 || windowMillis > 60000) {
            throw new IllegalArgumentException("Invalid TicketFlow Redis settings");
        }
    }
}
