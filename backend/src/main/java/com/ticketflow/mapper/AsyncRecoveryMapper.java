package com.ticketflow.mapper;

import com.ticketflow.model.entity.*;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AsyncRecoveryMapper {
    private final JdbcTemplate db;
    public AsyncRecoveryMapper(JdbcTemplate db) { this.db=db; }

    public List<Long> sessions(long after) {
        return db.queryForList("SELECT g.session_id FROM tf_async_gate g JOIN tf_session s ON s.id=g.session_id WHERE s.purchase_mode='ASYNC' AND g.session_id>? ORDER BY g.session_id LIMIT 100",Long.class,after);
    }
    /** Caller holds gate X. An abandoned maintenance lease can be replaced after 30 seconds. */
    public Long claim(long session,String owner) {
        int changed=db.update("UPDATE tf_async_gate SET phase='PAUSED',maintenance_owner=?,maintenance_version=maintenance_version+1,updated_at=UTC_TIMESTAMP(6) WHERE session_id=? AND (maintenance_owner IS NULL OR updated_at<UTC_TIMESTAMP(6)-INTERVAL 30 SECOND)",owner,session);
        return changed==0?null:db.queryForObject("SELECT maintenance_version FROM tf_async_gate WHERE session_id=?",Long.class,session);
    }
    public boolean owns(long session,long epoch,long version,String owner) {
        return db.queryForObject("SELECT COUNT(*) FROM tf_async_gate WHERE session_id=? AND epoch=? AND maintenance_version=? AND maintenance_owner=? AND updated_at>=UTC_TIMESTAMP(6)-INTERVAL 30 SECOND",Long.class,session,epoch,version,owner)==1;
    }
    public void advance(long session,long epoch,long version,String owner) {
        TradeMapper.requireOne(db.update("UPDATE tf_async_gate SET phase='REBUILDING',epoch=epoch+1 WHERE session_id=? AND epoch=? AND maintenance_version=? AND maintenance_owner=?",session,epoch,version,owner));
        // Gate X excludes all workers and lifecycle writes. Fence previously claimed work too.
        db.update("UPDATE tf_async_request SET epoch=?,work_version=work_version+1,state=IF(state='PROCESSING','RETRY_WAIT',state),next_retry_at=IF(state IN ('PROCESSING','RETRY_WAIT'),UTC_TIMESTAMP(6),next_retry_at),lease_owner=NULL,lease_until=NULL WHERE session_id=?",epoch+1,session);
    }
    public void ready(long session,long epoch,long version,String owner) {
        TradeMapper.requireOne(db.update("UPDATE tf_async_gate SET phase='READY',maintenance_owner=NULL,updated_at=UTC_TIMESTAMP(6) WHERE session_id=? AND epoch=? AND maintenance_version=? AND maintenance_owner=? AND phase='REBUILDING'",session,epoch,version,owner));
    }
    public List<String> pending(long session) {
        return db.queryForList("SELECT id FROM tf_async_request WHERE session_id=? AND state IN ('ACCEPTED','PROCESSING','RETRY_WAIT') ORDER BY user_id,id",String.class,session);
    }
    public List<AsyncToken> activeTokens(long session) {
        return db.query("SELECT r.*,o.status order_status FROM tf_async_request r LEFT JOIN tf_order o ON o.id=r.order_id WHERE r.session_id=? AND (r.state IN ('ACCEPTED','PROCESSING','RETRY_WAIT') OR (r.state='SUCCEEDED' AND o.status IN ('PENDING','PAID'))) ORDER BY r.user_id,r.id",
            (r,n)->new AsyncToken(r.getString("token"),r.getString("id"),r.getLong("user_id"),r.getString("request_key"),r.getString("payload_hash"),r.getLong("tier_id"),java.time.LocalDateTime.parse(r.getString("accepted_at").replace(' ','T')).toInstant(ZoneOffset.UTC).toEpochMilli(),r.getObject("order_id")==null?"ACCEPTED":"ORDER",r.getString("order_id")),session);
    }
    public List<Map<String,Object>> differences(long session) {
        return db.queryForList("""
            WITH tiers AS (SELECT id FROM tf_tier WHERE session_id=?),
            requests AS (SELECT * FROM tf_async_request WHERE session_id=?),
            orders AS (SELECT * FROM tf_order WHERE session_id=?)
            SELECT 'STOCK_BALANCE' category,CAST(t.id AS CHAR) resource_id FROM tiers t
            LEFT JOIN tf_stock s ON s.tier_id=t.id LEFT JOIN tf_async_tier_balance b ON b.tier_id=t.id
            WHERE s.tier_id IS NULL OR b.tier_id IS NULL OR s.capacity<>s.available+s.reserved+s.sold
              OR b.queued_count>s.available OR b.queued_count<>(SELECT COUNT(*) FROM requests r WHERE r.tier_id=t.id AND r.state IN ('ACCEPTED','PROCESSING','RETRY_WAIT'))
              OR s.reserved<>(SELECT COUNT(*) FROM orders o WHERE o.tier_id=t.id AND o.status='PENDING')
              OR s.sold<>(SELECT COUNT(*) FROM orders o WHERE o.tier_id=t.id AND o.status='PAID')
            UNION ALL SELECT 'ASYNC_SLOT',r.id FROM requests r LEFT JOIN tf_async_slot a ON a.request_id=r.id
            WHERE (r.state IN ('ACCEPTED','PROCESSING','RETRY_WAIT') AND (a.request_id IS NULL OR a.user_id<>r.user_id OR a.session_id<>r.session_id))
              OR (r.state IN ('SUCCEEDED','REJECTED') AND a.request_id IS NOT NULL)
            UNION ALL SELECT 'COMBINED_SLOT',CAST(a.user_id AS CHAR) FROM tf_async_slot a JOIN tf_purchase_slot p ON p.user_id=a.user_id AND p.session_id=a.session_id WHERE a.session_id=?
            UNION ALL SELECT 'ORDER_SLOT',CAST(o.id AS CHAR) FROM orders o LEFT JOIN tf_purchase_slot p ON p.order_id=o.id
            WHERE (o.status IN ('PENDING','PAID') AND (p.order_id IS NULL OR p.user_id<>o.user_id OR p.session_id<>o.session_id)) OR (o.status NOT IN ('PENDING','PAID') AND p.order_id IS NOT NULL)
            UNION ALL SELECT 'SUCCESS_ORDER',r.id FROM requests r LEFT JOIN orders o ON o.id=r.order_id
            WHERE r.state='SUCCEEDED' AND (o.id IS NULL OR o.user_id<>r.user_id OR o.tier_id<>r.tier_id OR r.result_code<>'OK')
            UNION ALL SELECT 'ASYNC_ORDER_ORIGIN',CAST(o.id AS CHAR) FROM orders o LEFT JOIN requests r ON r.order_id=o.id WHERE r.id IS NULL
            UNION ALL SELECT 'PROJECTION_SEQUENCE',CAST(b.tier_id AS CHAR) FROM tf_async_tier_balance b JOIN tiers t ON t.id=b.tier_id
            WHERE b.projection_version<>(SELECT COUNT(*) FROM tf_outbox e WHERE e.tier_id=t.id AND e.destination='REDIS')
              OR b.projection_version<>COALESCE((SELECT MAX(e.projection_seq) FROM tf_outbox e WHERE e.tier_id=t.id AND e.destination='REDIS'),0)
            UNION ALL SELECT 'STOCK_LOG',CAST(o.id AS CHAR) FROM orders o WHERE
              (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='RESERVE')<>1
              OR (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='PAY')<>IF(o.status IN ('PAID','REFUNDED'),1,0)
              OR (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='RELEASE')<>IF(o.status IN ('CANCELLED','CLOSED'),1,0)
              OR (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='REFUND')<>IF(o.status='REFUNDED',1,0)
            UNION ALL SELECT 'PAYMENT_REFUND',CAST(o.id AS CHAR) FROM orders o LEFT JOIN tf_payment p ON p.order_id=o.id LEFT JOIN tf_refund f ON f.order_id=o.id
            WHERE (o.status IN ('PAID','REFUNDED') AND (p.id IS NULL OR p.amount_fen<>o.amount_fen)) OR (o.status NOT IN ('PAID','REFUNDED') AND p.id IS NOT NULL)
              OR (o.status='REFUNDED' AND (f.id IS NULL OR f.amount_fen<>o.amount_fen)) OR (o.status<>'REFUNDED' AND f.id IS NOT NULL)
            """,session,session,session,session);
    }
}
