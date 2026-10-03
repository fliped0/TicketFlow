package com.ticketflow.mapper;

import com.ticketflow.model.entity.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminOrderMapper {
    private final JdbcTemplate db;
    public AdminOrderMapper(JdbcTemplate db) { this.db=db; }
    private static String ts(LocalDateTime t) { return t.toString().replace('T',' '); }
    private String where(AdminOrderFilter f, List<Object> args) {
        var sql=new StringBuilder(" WHERE 1=1");
        if (f.orderId()!=null) { sql.append(" AND o.id=?"); args.add(f.orderId()); }
        if (f.sessionId()!=null) { sql.append(" AND o.session_id=?"); args.add(f.sessionId()); }
        if (f.status()!=null) { sql.append(" AND o.status=?"); args.add(f.status()); }
        if (f.from()!=null) { sql.append(" AND o.created_at>=?"); args.add(ts(f.from())); }
        if (f.to()!=null) { sql.append(" AND o.created_at<?"); args.add(ts(f.to())); }
        return sql.toString();
    }
    public List<AdminOrderRecord> list(AdminOrderFilter filter, int size, int offset) {
        var args=new ArrayList<Object>(); String predicate=where(filter,args); args.add(size); args.add(offset);
        return db.query("SELECT o.*,p.id payment_id,p.amount_fen payment_amount,p.paid_at,r.id refund_id,r.amount_fen refund_amount,r.refunded_at FROM tf_order o LEFT JOIN tf_payment p ON p.order_id=o.id LEFT JOIN tf_refund r ON r.order_id=o.id"+predicate+" ORDER BY o.id DESC LIMIT ? OFFSET ?",
                (row,n)->new AdminOrderRecord(OrderMapper.ORDER.mapRow(row,n),
                        row.getObject("payment_id")==null?null:new PaymentRecord(row.getLong("payment_id"),row.getLong("id"),row.getLong("payment_amount"),LocalDateTime.parse(row.getString("paid_at").replace(' ','T'))),
                        row.getObject("refund_id")==null?null:new RefundRecord(row.getLong("refund_id"),row.getLong("id"),row.getLong("refund_amount"),LocalDateTime.parse(row.getString("refunded_at").replace(' ','T')))),args.toArray());
    }
    public long count(AdminOrderFilter filter) {
        var args=new ArrayList<Object>(); String predicate=where(filter,args);
        return db.queryForObject("SELECT COUNT(*) FROM tf_order o"+predicate,Long.class,args.toArray());
    }
    public long orderCount(LocalDateTime from, LocalDateTime to) { return db.queryForObject("SELECT COUNT(*) FROM tf_order WHERE created_at>=? AND created_at<?",Long.class,ts(from),ts(to)); }
    public long paidAmount(LocalDateTime from, LocalDateTime to) { return db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM tf_payment WHERE paid_at>=? AND paid_at<?",Long.class,ts(from),ts(to)); }
    public long refundAmount(LocalDateTime from, LocalDateTime to) { return db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM tf_refund WHERE refunded_at>=? AND refunded_at<?",Long.class,ts(from),ts(to)); }
}
