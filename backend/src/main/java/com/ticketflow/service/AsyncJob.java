package com.ticketflow.service;
import com.ticketflow.config.AsyncBrokerGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
@Service
@ConditionalOnProperty(name={"ticketflow.async.enabled","ticketflow.async.jobs-enabled"},havingValue="true")
public class AsyncJob {
    private final AsyncBrokerGateway broker;private final OutboxPublisher publisher;private final AsyncOrderService orders;
    public AsyncJob(AsyncBrokerGateway broker,OutboxPublisher publisher,AsyncOrderService orders) {this.broker=broker;this.publisher=publisher;this.orders=orders;}
    @EventListener(ApplicationReadyEvent.class) public void ready() {dispatch();recover();}
    @Scheduled(fixedDelay=500) public void dispatch() {
        try {broker.topology();broker.startConsumers();}
        catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_dispatch_unavailable type={}",failure.getClass().getSimpleName());}
        // Redis release/projection must continue even when RabbitMQ is unavailable.
        try {publisher.tick();}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_outbox_unavailable type={}",failure.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=2000) public void recover() {
        try {orders.sweep();}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_scan_unavailable type={}",failure.getClass().getSimpleName());}
    }
}
