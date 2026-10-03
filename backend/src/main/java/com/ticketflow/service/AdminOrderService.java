package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.mapper.AdminOrderMapper;
import com.ticketflow.model.entity.*;
import com.ticketflow.model.vo.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AdminOrderService {
    private final AdminOrderMapper db;
    private final JsonMapper json;
    public AdminOrderService(AdminOrderMapper db, JsonMapper json) { this.db=db; this.json=json; }
    private static BusinessException invalid() { return new BusinessException(400,"VALIDATION_ERROR","请求参数不合法"); }
    private static LocalDateTime utc(String value) {
        if (value==null) return null;
        try {
            var time=OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            if (time.getYear()<1000 || time.getYear()>9999 || time.getNano()%1000!=0) throw invalid();
            return time;
        } catch (DateTimeException e) { throw invalid(); }
    }
    private static String iso(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toString(); }
    private static void range(LocalDateTime from,LocalDateTime to) { if (from!=null && to!=null && !from.isBefore(to)) throw invalid(); }
    private AdminOrderVO view(AdminOrderRecord row) {
        var o=row.order(); var p=row.payment(); var r=row.refund();
        return new AdminOrderVO(Long.toString(o.id()),Long.toString(o.userId()),o.status().name(),o.quantity(),o.unitPriceFen(),o.amountFen(),json.readTree(o.snapshot()),iso(o.createdAt()),iso(o.expiresAt()),
                p==null?null:new PaymentVO(Long.toString(p.id()),p.amountFen(),iso(p.paidAt())),r==null?null:new RefundVO(Long.toString(r.id()),r.amountFen(),iso(r.refundedAt())));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CatalogVO.Page<AdminOrderVO> orders(String orderId,String sessionId,String status,String from,String to,Integer p,Integer s) {
        int page=p==null?1:p, size=s==null?20:s;
        if (page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE) throw invalid();
        if (status!=null) { try { OrderStatus.valueOf(status); } catch (IllegalArgumentException e) { throw invalid(); } }
        var start=utc(from); var end=utc(to); range(start,end);
        var filter=new AdminOrderFilter(orderId==null?null:OrderApplicationService.id(orderId),sessionId==null?null:OrderApplicationService.id(sessionId),status,start,end);
        return new CatalogVO.Page<>(db.list(filter,size,(page-1)*size).stream().map(this::view).toList(),page,size,db.count(filter));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StatisticsVO statistics(String from,String to) {
        var start=utc(from); var end=utc(to);
        if (start==null || end==null) throw invalid(); range(start,end);
        if (Duration.between(start,end).compareTo(Duration.ofDays(31))>0) throw invalid();
        long count=db.orderCount(start,end), paid=db.paidAmount(start,end), refund=db.refundAmount(start,end);
        return new StatisticsVO(iso(start),iso(end),count,paid,refund,Math.subtractExact(paid,refund),"created_at","paid_at","refunded_at");
    }
}
