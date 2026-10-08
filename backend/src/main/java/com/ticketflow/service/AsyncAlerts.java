package com.ticketflow.service;

import com.ticketflow.mapper.AsyncOperationsMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AsyncAlerts {
    private final AsyncOperationsMapper operations;
    private final MeterRegistry metrics;
    public AsyncAlerts(AsyncOperationsMapper operations,MeterRegistry metrics) {this.operations=operations;this.metrics=metrics;}
    public void raise(String category,String resource,String detail) {
        LoggerFactory.getLogger(getClass()).error("async_alert category={} resource={} detail={}",category,resource,detail);
        metrics.counter("ticketflow.async.alert","category",category).increment();
        try {operations.alert(category,resource,detail);}
        catch(RuntimeException unavailable) {LoggerFactory.getLogger(getClass()).error("async_alert_persistence_unavailable category={} resource={}",category,resource);}
    }
    public void resolve(String category,String resource) {operations.resolve(category,resource);}
}
