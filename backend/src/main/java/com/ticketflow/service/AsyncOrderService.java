package com.ticketflow.service;
import com.ticketflow.common.exception.*;
import com.ticketflow.mapper.*;
import com.ticketflow.model.entity.*;
import com.ticketflow.model.vo.OrderSnapshotVO;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class AsyncOrderService {
    private final AsyncTransactions tx;private final AsyncGateMapper gates;private final AsyncRequestMapper requests;
    private final OutboxMapper outbox;private final OrderMapper orders;private final TradeMapper users;
    private final InventoryService stock;private final DatabaseClock clock;private final AsyncEvents events;private final JsonMapper json;
    private final String owner=UUID.randomUUID().toString();
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public AsyncOrderService(AsyncTransactions tx,AsyncGateMapper gates,AsyncRequestMapper requests,OutboxMapper outbox,OrderMapper orders,TradeMapper users,InventoryService stock,DatabaseClock clock,AsyncEvents events,JsonMapper json,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.tx=tx;this.gates=gates;this.requests=requests;this.outbox=outbox;this.orders=orders;this.users=users;this.stock=stock;this.clock=clock;this.events=events;this.json=json;this.metrics=metrics;
    }
    private AsyncRequest locked(AsyncRequest route) {
        var gate=gates.lock(route.sessionId(),false);if(!"READY".equals(gate.phase()) || gate.epoch()!=route.epoch())throw new BusinessException(503,"ASYNC_PAUSED","场次暂不可用");
        if(!users.lockOwner(route.userId()))throw new IllegalStateException("Missing owner");return requests.get(route.id(),true);
    }
    public AsyncDisposition consume(AsyncMessage message) {
        var result=consumeMessage(message);metrics.counter("ticketflow.async.consume","outcome",result.name()).increment();return result;
    }
    private AsyncDisposition consumeMessage(AsyncMessage message) {
        var envelope=outbox.get(message.eventId());var route=requests.get(message.requestId(),false);
        if(envelope==null || route==null || !"BROKER".equals(envelope.destination()) || !envelope.aggregate().equals(route.id())
                || message.sessionId()!=route.sessionId() || message.epoch()!=route.epoch() || !json.readValue(envelope.payload(),AsyncMessage.class).equals(message))return AsyncDisposition.DEAD_LETTER;
        if(route.terminal())return AsyncDisposition.ACK;
        AsyncRequest work;
        try {
            work=tx.execute(()->{
                var r=locked(route);if(r.terminal())return null;var now=clock.nowUtc();
                if("PROCESSING".equals(r.state()) && now.isBefore(r.leaseUntil()))return null;
                if("RETRY_WAIT".equals(r.state()) && now.isBefore(r.nextRetry()) && now.isBefore(r.deadline()))return null;
                if(r.attempts()>=5 && now.isBefore(r.deadline()))return null;
                requests.claim(r,owner,now);return requests.get(r.id(),true);
            });
        } catch(RuntimeException unavailable) {return AsyncDisposition.STOP;}
        if(work==null)return AsyncDisposition.ACK;
        try {
            tx.execute(()->{
                var r=locked(work);if(r.terminal() || r.workVersion()!=work.workVersion() || !owner.equals(r.owner()))return null;
                var catalog=orders.lockCatalog(r.tierId());boolean qualified=orders.hasSlot(r.userId(),r.sessionId());
                if(!requests.hasSlot(r.userId(),r.sessionId()))throw new IllegalStateException("Missing queued slot");
                int available=stock.lock(r.tierId()),queued=requests.lockBalance(r.tierId());var now=clock.nowUtc();
                if(queued<1 || queued>available)throw new IllegalStateException("Invalid queued balance");
                String rejected=null;
                try {
                    if(!now.isBefore(r.deadline()))throw OrderPolicy.rejected("PROCESSING_DEADLINE_EXCEEDED");
                    // A stale worker lease is not evidence that the business request expired.
                    if(!now.isBefore(r.leaseUntil()))throw new IllegalStateException("Worker lease expired");
                    if(!users.lockUser(r.userId()))throw OrderPolicy.rejected("ACCOUNT_DISABLED");
                    if(catalog==null)throw OrderPolicy.rejected("NOT_FOUND");OrderPolicy.checkSale(catalog,now);
                    if(qualified)throw OrderPolicy.rejected("PURCHASE_LIMIT");if(available==0)throw OrderPolicy.rejected("SOLD_OUT");
                } catch(BusinessRejection failure) {rejected=failure.code();}
                if(rejected!=null) {requests.removeSlot(r);requests.balance(r.tierId(),-1);requests.complete(r,null,rejected,now);events.projection(r,"RELEASE",null,now);return null;}
                var snapshot=new OrderSnapshotVO(1,Long.toString(catalog.eventId()),catalog.eventName(),catalog.city(),catalog.venue(),Long.toString(catalog.sessionId()),catalog.startsAt().toInstant(ZoneOffset.UTC).toString(),Long.toString(r.tierId()),catalog.tierName(),catalog.priceFen(),1,catalog.priceFen(),catalog.refundPolicy());
                long order=orders.insert(r.userId(),catalog,json.writeValueAsString(snapshot),now,OrderPolicy.expiry(now,catalog.startsAt()));
                requests.removeSlot(r);orders.insertSlot(r.userId(),r.sessionId(),order);stock.reserve(r.tierId(),order,now);requests.balance(r.tierId(),-1);
                requests.complete(r,order,"OK",now);events.projection(r,"MATERIALIZE",order,now);return null;
            });return AsyncDisposition.ACK;
        } catch(RuntimeException failure) {
            try {
                return tx.execute(()->{
                    var r=locked(work);if(r.terminal() || r.workVersion()!=work.workVersion())return AsyncDisposition.ACK;
                    var now=clock.nowUtc();var next=now.plusSeconds(1L<<Math.min(3,r.attempts()-1));
                    if(!next.isBefore(r.deadline()))next=r.deadline();requests.retry(r,now,next);
                    if(r.attempts()<5)events.broker(r,next);
                    else org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_attempts_exhausted requestId={} eventId={}",r.id(),message.eventId());
                    return r.attempts()>=5?AsyncDisposition.DEAD_LETTER:AsyncDisposition.ACK;
                });
            } catch(RuntimeException recoveryFailed) {return AsyncDisposition.STOP;}
        }
    }
    /** Minimal lease/deadline liveness; batch 10 adds orphan and epoch reconstruction. */
    public void sweep() {
        long end=System.nanoTime()+5_000_000_000L;
        for(String id:requests.due()) {
            if(System.nanoTime()>end)break;
            try {
                var route=requests.get(id,false);
                tx.execute(()->{
                    var r=locked(route);if(r.terminal())return null;var now=clock.nowUtc();
                    if("PROCESSING".equals(r.state()) && now.isBefore(r.leaseUntil()))return null;
                    if(!now.isBefore(r.deadline())) {
                        orders.hasSlot(r.userId(),r.sessionId());requests.hasSlot(r.userId(),r.sessionId());stock.lock(r.tierId());requests.lockBalance(r.tierId());
                        requests.removeSlot(r);requests.balance(r.tierId(),-1);requests.complete(r,null,"PROCESSING_DEADLINE_EXCEEDED",now);events.projection(r,"RELEASE",null,now);
                    } else {requests.retry(r,now,now.plusSeconds(1));events.broker(r,now.plusSeconds(1));}
                    return null;
                });
            } catch(RuntimeException failure) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_scan_failed requestId={} type={}",id,failure.getClass().getSimpleName());}
        }
    }
}
