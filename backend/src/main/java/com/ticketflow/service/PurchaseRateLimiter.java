package com.ticketflow.service;

import com.ticketflow.common.exception.RateLimitedException;
import com.ticketflow.config.RedisFeatureProperties;
import com.ticketflow.config.RedisGateway;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.TradeMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PurchaseRateLimiter {
    private final RedisFeatureProperties settings;
    private final RedisGateway redis;
    private final OrderMapper orders;
    private final TradeMapper requests;
    private final MeterRegistry metrics;
    public PurchaseRateLimiter(RedisFeatureProperties settings, RedisGateway redis, OrderMapper orders,
                               TradeMapper requests, MeterRegistry metrics) {
        this.settings = settings; this.redis = redis; this.orders = orders;
        this.requests = requests; this.metrics = metrics;
    }
    private void count(String outcome) {
        metrics.counter("ticketflow.purchase.limit", "outcome", outcome).increment();
    }
    public void check(long user, long tier, String requestKey) {
        if (!settings.enabled()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Rate limiting must run before the trade transaction");
        if (requests.hasCreateRequest(user, requestKey)) { count("replayed"); return; }
        Long session = orders.sessionForTier(tier);
        if (session == null) return; // Preserve the original durable NOT_FOUND handling.
        String prefix = settings.namespace() + ":{purchase-limit}:";
        long wait;
        try {
            wait = redis.limit(List.of(prefix + "user:" + user, prefix + "session:" + session,
                    prefix + "attempt:" + user + ":" + TradeExecutor.hash(requestKey)),
                    settings.userLimit(), settings.sessionLimit(), settings.windowMillis());
        } catch (org.springframework.dao.DataAccessException error) {
            // SYNC still relies on DB locks/stock/qualification and its existing bounded pool.
            count("unavailable"); return;
        }
        if (wait > 0) { count("rejected"); throw new RateLimitedException((wait + 999) / 1000); }
        count(wait < 0 ? "inflight_replay" : "allowed");
    }
}
