package com.ticketflow.mapper;
import com.ticketflow.model.entity.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
@Repository
public class AsyncRequestMapper {
    private final JdbcTemplate db;
    public AsyncRequestMapper(JdbcTemplate db) {this.db=db;}
    // MySQL DATETIME stores a UTC wall clock, not a JVM-local Timestamp instant.
    private static LocalDateTime at(ResultSet r,String k)throws SQLException {String t=r.getString(k);return t==null?null:LocalDateTime.parse(t.replace(' ','T'));}
    private static final RowMapper<AsyncRequest> ROW=(r,n)->new AsyncRequest(r.getString("id"),r.getLong("user_id"),r.getLong("session_id"),r.getLong("tier_id"),
            r.getString("request_key"),r.getString("payload_hash"),r.getString("token"),r.getLong("epoch"),r.getString("state"),at(r,"accepted_at"),
            at(r,"create_deadline"),r.getObject("order_id",Long.class),r.getString("result_code"),r.getObject("http_status",Integer.class),
            r.getLong("work_version"),r.getString("lease_owner"),at(r,"lease_until"),r.getInt("attempt_count"),at(r,"next_retry_at"));
    private AsyncRequest one(List<AsyncRequest> rows) {return rows.isEmpty()?null:rows.get(0);}
    public AsyncRequest byKey(long user,String key,boolean lock) {return one(db.query("SELECT * FROM tf_async_request WHERE user_id=? AND request_key=?"+(lock?" FOR UPDATE":""),ROW,user,key));}
    public AsyncRequest get(String id,boolean lock) {return one(db.query("SELECT * FROM tf_async_request WHERE id=?"+(lock?" FOR UPDATE":""),ROW,id));}
    public AsyncRequest forOrder(long order) {return one(db.query("SELECT * FROM tf_async_request WHERE order_id=? FOR UPDATE",ROW,order));}
    public boolean hasSlot(long user,long session) {return !db.queryForList("SELECT request_id FROM tf_async_slot WHERE user_id=? AND session_id=? FOR UPDATE",String.class,user,session).isEmpty();}
    public int lockBalance(long tier) {return db.queryForObject("SELECT queued_count FROM tf_async_tier_balance WHERE tier_id=? FOR UPDATE",Integer.class,tier);}
    public void balance(long tier,int change) {TradeMapper.requireOne(db.update("UPDATE tf_async_tier_balance SET queued_count=queued_count+? WHERE tier_id=? AND queued_count+?>=0",change,tier,change));}
    public long sequence(long tier) {TradeMapper.requireOne(db.update("UPDATE tf_async_tier_balance SET projection_version=projection_version+1 WHERE tier_id=?",tier));return db.queryForObject("SELECT projection_version FROM tf_async_tier_balance WHERE tier_id=?",Long.class,tier);}
    public void insert(String id,long user,long session,long tier,String key,String hash,String token,long epoch,String state,
                       LocalDateTime now,LocalDateTime accepted,LocalDateTime deadline,String code,Integer status) {
        db.update("INSERT INTO tf_async_request(id,user_id,session_id,tier_id,request_key,payload_hash,token,epoch,state,created_at,updated_at,accepted_at,create_deadline,result_code,http_status,result_json,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?)",
                id,user,session,tier,key,hash,token,epoch,state,now,now,accepted,deadline,code,status,"REJECTED".equals(state)?"{}":null,"REJECTED".equals(state)?now:null);
    }
    public void slot(AsyncRequest r) {db.update("INSERT INTO tf_async_slot(user_id,session_id,request_id) VALUES(?,?,?)",r.userId(),r.sessionId(),r.id());}
    public void removeSlot(AsyncRequest r) {TradeMapper.requireOne(db.update("DELETE FROM tf_async_slot WHERE request_id=?",r.id()));}
    public void claim(AsyncRequest r,String owner,LocalDateTime now) {TradeMapper.requireOne(db.update("UPDATE tf_async_request SET state='PROCESSING',work_version=work_version+1,lease_owner=?,lease_until=?,attempt_count=attempt_count+1,updated_at=? WHERE id=?",owner,now.plusSeconds(8),now,r.id()));}
    public void retry(AsyncRequest r,LocalDateTime now,LocalDateTime next) {TradeMapper.requireOne(db.update("UPDATE tf_async_request SET state='RETRY_WAIT',lease_owner=NULL,lease_until=NULL,next_retry_at=?,updated_at=? WHERE id=? AND work_version=?",next,now,r.id(),r.workVersion()));}
    public void complete(AsyncRequest r,Long order,String code,LocalDateTime now) {TradeMapper.requireOne(db.update("UPDATE tf_async_request SET state=?,order_id=?,result_code=?,http_status=200,result_json='{}',completed_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND state IN ('ACCEPTED','PROCESSING','RETRY_WAIT')",order==null?"REJECTED":"SUCCEEDED",order,code,now,now,r.id()));}
    public List<String> due() {return db.queryForList("SELECT id FROM tf_async_request WHERE state IN ('ACCEPTED','PROCESSING','RETRY_WAIT') AND (create_deadline<=UTC_TIMESTAMP(6) OR (state='PROCESSING' AND lease_until<=UTC_TIMESTAMP(6)) OR (state='RETRY_WAIT' AND next_retry_at<=UTC_TIMESTAMP(6) AND attempt_count<5)) ORDER BY create_deadline,id LIMIT 100",String.class);}
    public List<Map<String,Object>> bootstrap(long session) {return db.queryForList("SELECT t.id,s.available,b.queued_count,b.projection_version FROM tf_tier t JOIN tf_stock s ON s.tier_id=t.id JOIN tf_async_tier_balance b ON b.tier_id=t.id WHERE t.session_id=? ORDER BY t.id",session);}
    public boolean history(long session) {return db.queryForObject("SELECT (SELECT COUNT(*) FROM tf_order WHERE session_id=?)+(SELECT COUNT(*) FROM tf_async_slot WHERE session_id=?)",Long.class,session,session)>0;}
}
