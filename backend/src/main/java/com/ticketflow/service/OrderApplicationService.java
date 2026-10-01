package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.mapper.OrderMapper;
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
    public OrderApplicationService(TradeExecutor executor, OrderMapper db, InventoryService stock, DatabaseClock clock, JsonMapper json) {
        this.executor=executor; this.db=db; this.stock=stock; this.clock=clock; this.json=json;
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
    private OrderDetailVO view(OrderRecord row) {
        return new OrderDetailVO(Long.toString(row.id()),row.status().name(),row.quantity(),row.unitPriceFen(),row.amountFen(),
                json.readTree(row.snapshot()),iso(row.createdAt()),iso(row.expiresAt()),null,null);
    }
    @Transactional(readOnly=true)
    public OrderDetailVO detail(long user, String orderId) {
        var row=db.owned(user,id(orderId));
        if (row==null) throw new BusinessException(404,"NOT_FOUND","资源不存在");
        return view(row);
    }
    @Transactional(readOnly=true)
    public OrderPageVO list(long user, String status, Integer suppliedPage, Integer suppliedSize) {
        int page=suppliedPage==null?1:suppliedPage, size=suppliedSize==null?20:suppliedSize;
        if (page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE) throw invalid();
        if (status!=null) { try { OrderStatus.valueOf(status); } catch (IllegalArgumentException error) { throw invalid(); } }
        return new OrderPageVO(db.list(user,status,size,(page-1)*size).stream().map(this::view).toList(),page,size,db.count(user,status));
    }
}
