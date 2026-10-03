package com.ticketflow.service;

import com.ticketflow.mapper.OrderMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="ticketflow.expiry.enabled",havingValue="true",matchIfMissing=true)
public class OrderExpiryJob {
    private final OrderMapper orders;
    private final OrderApplicationService service;
    private final DatabaseClock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    public OrderExpiryJob(OrderMapper orders, OrderApplicationService service, DatabaseClock clock) { this.orders=orders; this.service=service; this.clock=clock; }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { scan(); }
    @Scheduled(fixedDelay=10_000,initialDelay=10_000)
    public void scan() {
        if (!running.compareAndSet(false,true)) return;
        String previous=MDC.get("traceId"); MDC.put("traceId",UUID.randomUUID().toString());
        try {
            long deadline=System.nanoTime()+10_000_000_000L;
            LocalDateTime cutoff=clock.nowUtc(), after=null; long afterId=0;
            while (deadline-System.nanoTime()>=1_000_000_000L) {
                var batch=orders.expired(cutoff,after,afterId); if (batch.isEmpty()) break;
                for (var candidate:batch) {
                    if (deadline-System.nanoTime()<1_000_000_000L) return;
                    try { service.closeExpired(candidate.id(),deadline); }
                    catch (RuntimeException error) { LoggerFactory.getLogger(getClass()).error("Expiry failed order={} traceId={} type={}",candidate.id(),MDC.get("traceId"),error.getClass().getSimpleName()); }
                    after=candidate.expiresAt(); afterId=candidate.id();
                }
            }
        } catch (RuntimeException error) { LoggerFactory.getLogger(getClass()).error("Expiry scan failed traceId={} type={}",MDC.get("traceId"),error.getClass().getSimpleName()); }
        finally { if (previous==null) MDC.remove("traceId"); else MDC.put("traceId",previous); running.set(false); }
    }
}
