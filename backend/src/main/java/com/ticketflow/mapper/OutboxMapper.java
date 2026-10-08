package com.ticketflow.mapper;
import com.ticketflow.model.entity.OutboxEvent;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
@Repository
public class OutboxMapper {
    private final JdbcTemplate db;
    public OutboxMapper(JdbcTemplate db) {this.db=db;}
    private static final RowMapper<OutboxEvent> ROW=(r,n)->new OutboxEvent(r.getString("id"),r.getString("destination"),r.getString("event_type"),r.getString("aggregate_id"),r.getLong("session_id"),r.getLong("tier_id"),r.getLong("epoch"),r.getObject("projection_seq",Long.class),r.getString("payload"),r.getLong("claim_version"),r.getInt("attempt_count"));
    public OutboxEvent get(String id) {var rows=db.query("SELECT * FROM tf_outbox WHERE id=?",ROW,id);return rows.isEmpty()?null:rows.get(0);}
    public List<OutboxEvent> projections(long tier,long after,long through) {
        return db.query("SELECT * FROM tf_outbox WHERE tier_id=? AND destination='REDIS' AND projection_seq>? AND projection_seq<=? ORDER BY projection_seq LIMIT 100",ROW,tier,after,through);
    }
    public void insert(String id,String destination,String type,String aggregate,long session,long tier,long epoch,Long seq,String payload,LocalDateTime at) {
        db.update("INSERT INTO tf_outbox(id,destination,event_type,aggregate_id,session_id,tier_id,epoch,projection_seq,payload,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",id,destination,type,aggregate,session,tier,epoch,seq,payload,at);
    }
    public OutboxEvent claim(String destination,String owner,LocalDateTime now) {
        var rows=db.query("SELECT * FROM tf_outbox WHERE destination=? AND ((state='PENDING' AND next_attempt_at<=?) OR (state='SENDING' AND lease_until<=?)) ORDER BY next_attempt_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",ROW,destination,now,now);
        if(rows.isEmpty())return null;var r=rows.get(0);
        db.update("UPDATE tf_outbox SET state='SENDING',claim_version=claim_version+1,attempt_count=attempt_count+1,lease_owner=?,lease_until=? WHERE id=?",owner,now.plusSeconds(8),r.id());
        return get(r.id());
    }
    public void settle(OutboxEvent r,String owner,boolean success,LocalDateTime now) {
        db.update("UPDATE tf_outbox SET state=?,sent_at=?,next_attempt_at=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND state='SENDING' AND claim_version=? AND lease_owner=?",
                success?"SENT":"PENDING",success?now:null,now.plusSeconds(Math.min(30,1L<<Math.min(5,r.attempts()-1))),r.id(),r.claimVersion(),owner);
    }
}
