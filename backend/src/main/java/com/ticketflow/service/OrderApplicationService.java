package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.PaymentMapper;
import com.ticketflow.model.dto.CreateOrderDTO;
import com.ticketflow.model.entity.OrderRecord;
import com.ticketflow.model.entity.OrderStatus;
import com.ticketflow.model.entity.TradeOperation;
import com.ticketflow.model.vo.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OrderApplicationService {
    private final TradeExecutor executor;
    private final OrderMapper db;
    private final InventoryService stock;
    private final DatabaseClock clock;
    private final JsonMapper json;
    private final PaymentMapper payments;
    private final PaymentSimulator simulator;
    public OrderApplicationService(TradeExecutor executor, OrderMapper db, InventoryService stock, DatabaseClock clock, JsonMapper json,
                                   PaymentMapper payments, PaymentSimulator simulator) {
        this.executor=executor; this.db=db; this.stock=stock; this.clock=clock; this.json=json; this.payments=payments; this.simulator=simulator;
    }
    private static BusinessException invalid() { return new BusinessException(400,"VALIDATION_ERROR","请求参数不合法"); }
    public static long id(String value) {
        if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException error) { throw invalid(); }
    }
    private static String iso(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toString(); }
    public TradeOutcome create(long user, String key, CreateOrderDTO input) {
        if (input==null || input.quantity()==null || input.quantity()!=1) throw invalid();
        long tier=id(input.tierId());
        TradeExecutor.validateKey(key);
        return executor.execute(user,TradeOperation.CREATE,key,TradeExecutor.hash("CREATE:v1\ntierId="+tier+"\nquantity=1"),201,()->createLocked(user,tier));
    }
    private TradeResultVO createLocked(long user, long tier) {
        var catalog=db.lockCatalog(tier);
        if (catalog==null) throw new BusinessRejection(404,"NOT_FOUND","资源不存在");
        if (db.hasSlot(user,catalog.sessionId())) throw OrderPolicy.rejected("PURCHASE_LIMIT");
        int available=stock.lock(tier);
        LocalDateTime now=clock.nowUtc(); // New SQL statement after all business locks.
        OrderPolicy.checkSale(catalog,now);
        if (available==0) throw OrderPolicy.rejected("SOLD_OUT");
        LocalDateTime expiry=OrderPolicy.expiry(now,catalog.startsAt());
        var snapshot=new OrderSnapshotVO(1,Long.toString(catalog.eventId()),catalog.eventName(),catalog.city(),catalog.venue(),
                Long.toString(catalog.sessionId()),iso(catalog.startsAt()),Long.toString(tier),catalog.tierName(),catalog.priceFen(),1,catalog.priceFen(),catalog.refundPolicy());
        long order=db.insert(user,catalog,json.writeValueAsString(snapshot),now,expiry);
        db.insertSlot(user,catalog.sessionId(),order);
        stock.reserve(tier,order,now);
        return new TradeResultVO(Long.toString(order),OrderStatus.PENDING.name(),OrderStatus.PENDING.name(),catalog.priceFen(),iso(expiry));
    }
    private OrderDetailVO view(OrderRecord row, boolean detail) {
        var payment=detail?payments.payment(row.id()):null; var refund=detail?payments.refund(row.id()):null;
        return new OrderDetailVO(Long.toString(row.id()),row.status().name(),row.quantity(),row.unitPriceFen(),row.amountFen(),
                json.readTree(row.snapshot()),iso(row.createdAt()),iso(row.expiresAt()),
                payment==null?null:new PaymentVO(Long.toString(payment.id()),payment.amountFen(),iso(payment.paidAt())),
                refund==null?null:new RefundVO(Long.toString(refund.id()),refund.amountFen(),iso(refund.refundedAt())));
    }
    public TradeOutcome cancel(long user, String key, String orderId) { return lifecycle(user,key,orderId,TradeOperation.CANCEL); }
    public TradeOutcome pay(long user, String key, String orderId) { return lifecycle(user,key,orderId,TradeOperation.PAY); }
    public TradeOutcome refund(long user, String key, String orderId) { return lifecycle(user,key,orderId,TradeOperation.REFUND); }
    private TradeOutcome lifecycle(long user, String key, String orderId, TradeOperation operation) {
        long order=id(orderId); TradeExecutor.validateKey(key);
        return executor.execute(user,operation,key,TradeExecutor.hash(operation.name()+":v1\norderId="+order),200,()->{
            var row=db.lockOwned(user,order);
            if (row==null) throw new BusinessRejection(404,"NOT_FOUND","资源不存在");
            return switch (operation) {
                case CANCEL -> cancelLocked(row);
                case PAY -> payLocked(row);
                case REFUND -> refundLocked(row);
                default -> throw new IllegalArgumentException("Unsupported lifecycle operation");
            };
        });
    }
    private static BusinessRejection stateConflict() { return new BusinessRejection(409,"ORDER_STATE_CONFLICT","订单状态不允许该操作"); }
    private static TradeResultVO result(OrderRecord order, OrderStatus operationStatus, OrderStatus current, Long payment, Long refund) {
        return new TradeResultVO(Long.toString(order.id()),operationStatus.name(),current.name(),order.amountFen(),iso(order.expiresAt()),
                payment==null?null:Long.toString(payment),refund==null?null:Long.toString(refund));
    }
    private LocalDateTime lockBalances(OrderRecord order) {
        db.requireSlot(order); stock.lock(order.tierId()); return clock.nowUtc();
    }
    private TradeResultVO cancelLocked(OrderRecord order) {
        if (order.status()==OrderStatus.CANCELLED || order.status()==OrderStatus.CLOSED) return result(order,order.status(),order.status(),null,null);
        if (order.status()!=OrderStatus.PENDING) throw stateConflict();
        LocalDateTime now=lockBalances(order);
        OrderStatus target=now.isBefore(order.expiresAt())?OrderStatus.CANCELLED:OrderStatus.CLOSED;
        releaseLocked(order,target,now);
        return result(order,target,target,null,null);
    }
    private void releaseLocked(OrderRecord order, OrderStatus target, LocalDateTime now) {
        db.transition(order,target,now); stock.release(order.tierId(),order.id(),now); db.deleteSlot(order);
    }
    private TradeResultVO payLocked(OrderRecord order) {
        var payment=payments.payment(order.id());
        if (payment!=null) {
            if (payment.amountFen()!=order.amountFen() || (order.status()!=OrderStatus.PAID && order.status()!=OrderStatus.REFUNDED)) throw new IllegalStateException("Payment mismatch");
            return result(order,OrderStatus.PAID,order.status(),payment.id(),null);
        }
        if (order.status()==OrderStatus.PAID || order.status()==OrderStatus.REFUNDED) throw new IllegalStateException("Missing successful payment");
        if (order.status()!=OrderStatus.PENDING) throw stateConflict();
        LocalDateTime now=lockBalances(order); OrderPolicy.checkPayment(now,order.expiresAt());
        if (!simulator.pay(order.id(),order.amountFen())) throw new BusinessRejection(422,"PAYMENT_SIMULATED_FAILURE","模拟支付失败");
        db.transition(order,OrderStatus.PAID,now); stock.markSold(order.tierId(),order.id(),now);
        long id=payments.insertPayment(order.id(),order.amountFen(),now);
        return result(order,OrderStatus.PAID,OrderStatus.PAID,id,null);
    }
    private TradeResultVO refundLocked(OrderRecord order) {
        var refund=payments.refund(order.id()); var payment=payments.payment(order.id());
        if (refund!=null) {
            if (order.status()!=OrderStatus.REFUNDED || refund.amountFen()!=order.amountFen() || payment==null || payment.amountFen()!=order.amountFen()) throw new IllegalStateException("Refund mismatch");
            return result(order,OrderStatus.REFUNDED,order.status(),payment.id(),refund.id());
        }
        if (order.status()==OrderStatus.REFUNDED) throw new IllegalStateException("Missing successful refund");
        if (order.status()!=OrderStatus.PAID) throw stateConflict();
        if (payment==null || payment.amountFen()!=order.amountFen()) throw new IllegalStateException("Missing or mismatched payment");
        LocalDateTime now=lockBalances(order); OrderPolicy.checkRefund(now,order.startsAt());
        if (!simulator.refund(order.id(),order.amountFen())) throw new BusinessRejection(422,"REFUND_SIMULATED_FAILURE","模拟退款失败");
        db.transition(order,OrderStatus.REFUNDED,now); stock.refund(order.tierId(),order.id(),now);
        long id=payments.insertRefund(order.id(),order.amountFen(),now); db.deleteSlot(order);
        return result(order,OrderStatus.REFUNDED,OrderStatus.REFUNDED,payment.id(),id);
    }
    public boolean closeExpired(long orderId) { return closeExpired(orderId,System.nanoTime()+5_000_000_000L); }
    public boolean closeExpired(long orderId, long deadline) {
        Long owner=db.owner(orderId); if (owner==null) return false;
        return executor.internal(owner,deadline,()->{
            var order=db.lockOwned(owner,orderId);
            if (order==null || order.status()!=OrderStatus.PENDING) return false;
            LocalDateTime now=lockBalances(order);
            if (now.isBefore(order.expiresAt())) return false;
            releaseLocked(order,OrderStatus.CLOSED,now); return true;
        });
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OrderDetailVO detail(long user, String orderId) {
        var row=db.owned(user,id(orderId));
        if (row==null) throw new BusinessException(404,"NOT_FOUND","资源不存在");
        return view(row,true);
    }
    @Transactional(readOnly=true)
    public OrderPageVO list(long user, String status, Integer suppliedPage, Integer suppliedSize) {
        int page=suppliedPage==null?1:suppliedPage, size=suppliedSize==null?20:suppliedSize;
        if (page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE) throw invalid();
        if (status!=null) { try { OrderStatus.valueOf(status); } catch (IllegalArgumentException error) { throw invalid(); } }
        return new OrderPageVO(db.list(user,status,size,(page-1)*size).stream().map(row->view(row,false)).toList(),page,size,db.count(user,status));
    }
}
