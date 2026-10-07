package com.ticketflow.service;
import com.ticketflow.common.exception.*;
import com.ticketflow.config.*;
import com.ticketflow.mapper.*;
import com.ticketflow.model.dto.CreateOrderDTO;
import com.ticketflow.model.entity.*;
import com.ticketflow.model.vo.*;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
@Service
public class PurchaseRequestService {
    private final AsyncTransactions tx;private final AsyncGateMapper gates;private final AsyncRequestMapper requests;private final TradeMapper users;
    private final OrderMapper orders;private final InventoryService stock;private final DatabaseClock clock;private final AsyncRedisGateway redis;private final AsyncEvents events;private final AsyncProperties settings;private final JsonMapper json;
    private final AsyncQueryLimiter queries;private final io.micrometer.core.instrument.MeterRegistry metrics;
    public PurchaseRequestService(AsyncTransactions tx,AsyncGateMapper gates,AsyncRequestMapper requests,TradeMapper users,OrderMapper orders,InventoryService stock,DatabaseClock clock,AsyncRedisGateway redis,AsyncEvents events,AsyncProperties settings,JsonMapper json,AsyncQueryLimiter queries,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.tx=tx;this.gates=gates;this.requests=requests;this.users=users;this.orders=orders;this.stock=stock;this.clock=clock;this.redis=redis;this.events=events;this.settings=settings;this.json=json;this.queries=queries;this.metrics=metrics;
    }
    private static BusinessException error(int status,String code) {return new BusinessException(status,code,"抢票请求暂不能处理");}
    private static String iso(LocalDateTime at) {return at==null?null:at.toInstant(ZoneOffset.UTC).toString();}
    public PurchaseOutcome outcome(AsyncRequest r,boolean replay) {
        var order=r.orderId()==null?null:orders.owned(r.userId(),r.orderId());
        if(r.orderId()!=null && order==null)throw new IllegalStateException("Missing async order");
        var view=new PurchaseRequestVO(r.id(),r.state(),iso(r.acceptedAt()),iso(r.deadline()),r.orderId()==null?null:r.orderId().toString(),"REJECTED".equals(r.state())?r.code():null,order==null?null:order.status().name(),order==null?null:json.readTree(order.snapshot()));
        int status=r.terminal()?(r.acceptedAt()==null?r.httpStatus():200):202;
        return new PurchaseOutcome(status,r.acceptedAt()==null?r.code():"OK",r.terminal()?"处理完成":"已受理",view,replay);
    }
    private AsyncRequest replay(AsyncRequest r,String hash) {if(!r.hash().equals(hash))throw error(409,"IDEMPOTENCY_CONFLICT");return r;}
    private void pause(long session,long epoch,String cause) {
        tx.execute(()->{var gate=gates.lock(session,true);if(gate.epoch()==epoch && "ASYNC".equals(gate.mode()))gates.pause(session);return null;});
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_admission_paused sessionId={} cause={}",session,cause);
    }
    public PurchaseOutcome submit(long user,String key,CreateOrderDTO input) {
        try {
            var result=submitActual(user,key,input);
            observed(result.replayed()?"REPLAYED":result.httpStatus()==202?"ACCEPTED":result.code());return result;
        }catch(BusinessException failure){observed(failure.code());throw failure;}
        catch(RuntimeException unavailable){observed("SYSTEM_ERROR");throw unavailable;}
    }
    private void observed(String code) {
        String bounded=java.util.Set.of("ACCEPTED","REPLAYED","SOLD_OUT","PURCHASE_LIMIT","RATE_LIMITED","ASYNC_PAUSED","ADMISSION_EXPIRED","PROCESSING_DEADLINE_EXCEEDED","DEPENDENCY_UNAVAILABLE","SYSTEM_ERROR").contains(code)?code:"OTHER";
        metrics.counter("ticketflow.async.admission","outcome",bounded).increment();
    }
    private PurchaseOutcome submitActual(long user,String key,CreateOrderDTO input) {
        if(input==null || input.quantity()==null || input.quantity()!=1)throw error(400,"VALIDATION_ERROR");
        long tier=OrderApplicationService.id(input.tierId());TradeExecutor.validateKey(key);String hash=TradeExecutor.hash("ASYNC:v1\ntierId="+tier+"\nquantity=1");
        var previous=requests.byKey(user,key,false);if(previous!=null)return outcome(replay(previous,hash),true);
        Long session=orders.sessionForTier(tier);if(session==null)throw error(404,"NOT_FOUND");var gate=gates.read(session);
        if(!"ASYNC".equals(gate.mode()))throw error(409,"SYNC_REQUIRED");
        if(!settings.enabled())throw error(503,"ASYNC_DISABLED");if(!"READY".equals(gate.phase()))throw error(503,"ASYNC_PAUSED");
        String candidate=UUID.randomUUID().toString();AsyncReservation reservation;
        try {reservation=redis.reserve(session,gate.epoch(),user,TradeExecutor.hash(key),hash,tier,candidate,UUID.randomUUID().toString(),clock.nowUtc().toInstant(ZoneOffset.UTC).toEpochMilli());}
        catch(RuntimeException unknown) {pause(session,gate.epoch(),"unknown_redis_result");throw error(503,"DEPENDENCY_UNAVAILABLE");}
        if("RATE_LIMITED".equals(reservation.code()))throw new RateLimitedException(1);
        if("IDEMPOTENCY_CONFLICT".equals(reservation.code()))throw error(409,"IDEMPOTENCY_CONFLICT");
        if(java.util.List.of("ASYNC_PAUSED","MISSING","CLOCK_SKEW").contains(reservation.code())) {
            pause(session,gate.epoch(),reservation.code());throw error(503,reservation.code().equals("ASYNC_PAUSED")?"ASYNC_PAUSED":"DEPENDENCY_UNAVAILABLE");
        }
        var result=tx.execute(()->{
            var current=gates.lock(session,false);if(!users.lockOwner(user))throw error(401,"UNAUTHENTICATED");
            var existing=requests.byKey(user,key,true);
            if(existing!=null) {
                if(reservation.token()!=null && !reservation.token().equals(existing.token())) {
                    orders.lockCatalog(tier);orders.hasSlot(user,session);requests.hasSlot(user,session);stock.lock(tier);requests.lockBalance(tier);
                    String event=UUID.nameUUIDFromBytes(("RELEASE:"+reservation.token()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    events.projection(event,reservation.requestId(),user,session,tier,gate.epoch(),reservation.token(),"RELEASE",null,clock.nowUtc());
                }
                // Returning conflict rather than throwing ensures losing candidate RELEASE commits.
                if(!existing.hash().equals(hash))return new PurchaseOutcome(409,"IDEMPOTENCY_CONFLICT","请求键已用于不同参数",null,true);
                return outcome(existing,true);
            }
            if(!"ASYNC".equals(current.mode()) || current.epoch()!=gate.epoch() || !"READY".equals(current.phase()))throw error(503,"ASYNC_PAUSED");
            var catalog=orders.lockCatalog(tier);if(catalog==null)throw error(404,"NOT_FOUND");
            boolean orderSlot=orders.hasSlot(user,session),asyncSlot=requests.hasSlot(user,session);
            boolean qualified=orderSlot || asyncSlot;int available=stock.lock(tier),queued=requests.lockBalance(tier);var now=clock.nowUtc();
            String rejected=null;
            try {
                if(!users.lockUser(user))throw OrderPolicy.rejected("ACCOUNT_DISABLED");
                OrderPolicy.checkSale(catalog,now);
                if(qualified)throw OrderPolicy.rejected("PURCHASE_LIMIT");
                if(!java.util.List.of("RESERVED","REPLAY").contains(reservation.code()))throw OrderPolicy.rejected(reservation.code());
                if(now.toInstant(ZoneOffset.UTC).toEpochMilli()>=reservation.reservedAtMillis()+10_000)throw OrderPolicy.rejected("ADMISSION_EXPIRED");
                if(queued>=available)throw OrderPolicy.rejected("SOLD_OUT");
            } catch(BusinessRejection failure) {rejected=failure.code();}
            if(rejected!=null) {
                requests.insert(reservation.requestId(),user,session,tier,key,hash,reservation.token(),gate.epoch(),"REJECTED",now,null,null,rejected,409);
                var r=requests.get(reservation.requestId(),true);events.projection(r,"RELEASE",null,now);return outcome(r,false);
            }
            var deadline=now.plusSeconds(30);if(catalog.saleEndAt().isBefore(deadline))deadline=catalog.saleEndAt();if(catalog.startsAt().isBefore(deadline))deadline=catalog.startsAt();
            requests.insert(reservation.requestId(),user,session,tier,key,hash,reservation.token(),gate.epoch(),"ACCEPTED",now,now,deadline,null,null);
            var r=requests.get(reservation.requestId(),true);requests.slot(r);requests.balance(tier,1);events.broker(r,now);events.projection(r,"ACTIVATE",null,now);
            return outcome(r,false);
        });
        return result;
    }
    public PurchaseRequestVO get(long user,String id) {
        try {UUID.fromString(id);}catch(IllegalArgumentException e){throw error(400,"VALIDATION_ERROR");}
        return queries.query(user,()->tx.snapshot(()->{
            var r=requests.get(id,false);if(r==null || r.userId()!=user)throw error(404,"NOT_FOUND");return outcome(r,false).data();
        }));
    }
}
