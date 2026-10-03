package com.ticketflow.mapper;

import com.ticketflow.model.entity.PaymentRecord;
import com.ticketflow.model.entity.RefundRecord;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentMapper {
    private final JdbcTemplate db;
    public PaymentMapper(JdbcTemplate db) { this.db=db; }
    public PaymentRecord payment(long order) {
        var rows=db.query("SELECT * FROM tf_payment WHERE order_id=?",(r,n)->new PaymentRecord(r.getLong("id"),r.getLong("order_id"),r.getLong("amount_fen"),LocalDateTime.parse(r.getString("paid_at").replace(' ','T'))),order);
        return rows.isEmpty()?null:rows.get(0);
    }
    public RefundRecord refund(long order) {
        var rows=db.query("SELECT * FROM tf_refund WHERE order_id=?",(r,n)->new RefundRecord(r.getLong("id"),r.getLong("order_id"),r.getLong("amount_fen"),LocalDateTime.parse(r.getString("refunded_at").replace(' ','T'))),order);
        return rows.isEmpty()?null:rows.get(0);
    }
    private long insert(String sql, long order, long amount, LocalDateTime time) {
        var key=new GeneratedKeyHolder();
        TradeMapper.requireOne(db.update(connection->{
            PreparedStatement s=connection.prepareStatement(sql,new String[]{"id"});
            s.setLong(1,order); s.setLong(2,amount); s.setString(3,time.toString().replace('T',' ')); return s;
        },key));
        return key.getKey().longValue();
    }
    public long insertPayment(long order, long amount, LocalDateTime time) { return insert("INSERT INTO tf_payment(order_id,amount_fen,paid_at) VALUES(?,?,?)",order,amount,time); }
    public long insertRefund(long order, long amount, LocalDateTime time) { return insert("INSERT INTO tf_refund(order_id,amount_fen,refunded_at) VALUES(?,?,?)",order,amount,time); }
}
