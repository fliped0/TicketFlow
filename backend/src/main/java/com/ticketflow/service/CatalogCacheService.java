package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.config.RedisFeatureProperties;
import com.ticketflow.config.RedisGateway;
import com.ticketflow.mapper.CatalogMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CatalogCacheService {
    private final RedisFeatureProperties settings;
    private final RedisGateway redis;
    private final CatalogMapper db;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    private final Semaphore queries;
    private final ReentrantLock[] stripes = new ReentrantLock[64];
    private final TransactionTemplate snapshot;
    private final AtomicLong retryRedisAt = new AtomicLong();

    public CatalogCacheService(RedisFeatureProperties settings, RedisGateway redis, CatalogMapper db,
                               JsonMapper json, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.settings = settings; this.redis = redis; this.db = db; this.json = json; this.metrics = metrics;
        queries = new Semaphore(settings.queryConcurrency());
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock();
        snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private void count(String outcome) {
        metrics.counter("ticketflow.catalog.cache", "outcome", outcome).increment();
    }
    public <T> T snapshot(Supplier<T> action) { return snapshot.execute(status -> action.get()); }
    /** Covers both cache misses and the live DB fields needed after cache hits. */
    public <T> T query(Supplier<T> action) {
        if (!settings.enabled()) return action.get();
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Catalog cache must run outside a DB transaction");
        if (!queries.tryAcquire()) {
            count("backpressure");
            throw new BusinessException(503, "CATALOG_BUSY", "目录查询繁忙，请稍后重试");
        }
        try { return action.get(); } finally { queries.release(); }
    }
    public <T> T get(String kind, List<?> parameters, TypeReference<T> type,
                     Supplier<T> loader, Predicate<T> empty) {
        if (!settings.enabled()) return snapshot.execute(status -> loader.get());
        // This durable generation advances in the same commit as every catalog change.
        long revision = db.revision();
        String key = settings.namespace() + ":catalog:v1:" + revision + ":" + kind + ":"
                + TradeExecutor.hash(json.writeValueAsString(parameters));
        String cached = read(key);
        if (cached != null) {
            try { T value=json.readValue(cached,type); empty.test(value); count("hit"); return value; }
            catch (RuntimeException error) { count("corrupt"); }
        }
        count("miss");
        ReentrantLock stripe = stripes[Math.floorMod(key.hashCode(), stripes.length)];
        boolean locked;
        try { locked = stripe.tryLock(settings.mergeWaitMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "CATALOG_BUSY", "目录查询繁忙，请稍后重试");
        }
        if (!locked) {
            count("backpressure");
            throw new BusinessException(503, "CATALOG_BUSY", "目录查询繁忙，请稍后重试");
        }
        try {
            cached = read(key);
            if (cached != null) {
                try { T value=json.readValue(cached,type); empty.test(value); count("merged_hit"); return value; }
                catch (RuntimeException error) { count("corrupt"); }
            }
            T value = snapshot.execute(status -> loader.get());
            count("load");
            // Even an old snapshot can only fill its old generation; TTL retires it.
            if (System.nanoTime() >= retryRedisAt.get()) {
                try { redis.put(key, json.writeValueAsString(value),
                        empty.test(value) ? settings.emptyTtlSeconds() : settings.cacheTtlSeconds()); }
                catch (org.springframework.dao.DataAccessException error) { unavailable(); }
            }
            return value;
        } finally { stripe.unlock(); }
    }
    private String read(String key) {
        if (System.nanoTime() < retryRedisAt.get()) return null;
        try {
            String value = redis.get(key);
            if (value != null) {
                // Corrupt payloads are misses, never public 500s or trusted business facts.
                try { json.readTree(value); }
                catch (RuntimeException error) { count("corrupt"); return null; }
            }
            return value;
        } catch (org.springframework.dao.DataAccessException error) {
            unavailable(); return null;
        }
    }
    private void unavailable() {
        retryRedisAt.set(System.nanoTime()+TimeUnit.SECONDS.toNanos(1)); count("unavailable");
    }
}
