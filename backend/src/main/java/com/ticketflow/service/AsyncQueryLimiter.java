package com.ticketflow.service;

import com.ticketflow.common.exception.RateLimitedException;
import com.ticketflow.config.AsyncProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Single-process, bounded result-query quota, independent of Redis/new-admission quotas. */
@Service
public class AsyncQueryLimiter {
    private final int limit;
    private final Semaphore permits;
    private final Map<Long,Integer> counts=new HashMap<>();
    private long window;
    public AsyncQueryLimiter(AsyncProperties settings) {
        limit=settings.queryLimit();permits=new Semaphore(settings.queryConcurrency());
    }
    private synchronized void check(long user) {
        long now=System.nanoTime()/1_000_000_000L;
        if(window!=now){counts.clear();window=now;}
        int count=counts.getOrDefault(user,0);
        if(count>=limit || count==0&&counts.size()>=4096)throw new RateLimitedException(1);
        counts.put(user,count+1);
    }
    public <T>T query(long user,Supplier<T> read) {
        check(user);if(!permits.tryAcquire())throw new RateLimitedException(1);
        try{return read.get();}finally{permits.release();}
    }
}
