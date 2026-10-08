package com.ticketflow.mapper;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AsyncOperationsMapper {
    private final JdbcTemplate db;
    public AsyncOperationsMapper(JdbcTemplate db) {this.db=db;}
    public void alert(String category,String resource,String detail) {
        db.update("INSERT INTO tf_async_alert(alert_key,category,resource_id,detail,first_seen,last_seen) VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE detail=?,occurrences=occurrences+1,last_seen=UTC_TIMESTAMP(6),resolved_at=NULL",category+":"+resource,category,resource,detail,detail);
    }
    public void resolve(String category,String resource) {db.update("UPDATE tf_async_alert SET resolved_at=UTC_TIMESTAMP(6) WHERE alert_key=? AND resolved_at IS NULL",category+":"+resource);}
    public List<Map<String,Object>> alerts() {return db.queryForList("SELECT category,resource_id,detail,occurrences,first_seen,last_seen FROM tf_async_alert WHERE resolved_at IS NULL ORDER BY last_seen DESC LIMIT 100");}
    public boolean replayed(String event) {return db.queryForObject("SELECT COUNT(*) FROM tf_async_dead_replay WHERE event_id=?",Long.class,event)>0;}
    public void receipt(String event,String request) {db.update("INSERT INTO tf_async_dead_replay(event_id,request_id,replayed_at) VALUES(?,?,UTC_TIMESTAMP(6))",event,request);}
    public void retry(String request) {TradeMapper.requireOne(db.update("UPDATE tf_async_request SET state='RETRY_WAIT',work_version=work_version+1,attempt_count=0,lease_owner=NULL,lease_until=NULL,next_retry_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=? AND state IN ('ACCEPTED','PROCESSING','RETRY_WAIT')",request));}
}
