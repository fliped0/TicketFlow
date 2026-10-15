package com.ticketflow;

import com.ticketflow.mapper.AsyncRequestMapper;
import com.ticketflow.service.AsyncOrderService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL scan/transaction test; no Redis or RabbitMQ is required. */
class BackgroundSweepIT extends OrderTestSupport {
    @Autowired AsyncRequestMapper requests;
    @Autowired AsyncOrderService worker;

    String accepted(Fixture f, LocalDateTime deadline) {
        return new TransactionTemplate(manager).execute(status -> {
            String username="scan_"+key().replace("-","").substring(0,20);
            db.update("INSERT INTO tf_user(username,password_hash,enabled,created_at) VALUES(?, 'test-no-login',FALSE,UTC_TIMESTAMP(6))",username);
            long user=db.queryForObject("SELECT id FROM tf_user WHERE username=?",Long.class,username);
            String id=key();
            requests.insert(id,user,f.session(),f.tier(),key(),
                    com.ticketflow.service.TradeExecutor.hash("ASYNC:v1\ntierId="+f.tier()+"\nquantity=1"),
                    key(),1,"ACCEPTED",deadline.minusSeconds(30),deadline.minusSeconds(30),deadline,null,null);
            var row=requests.get(id,true);
            requests.slot(row);requests.balance(f.tier(),1);
            return id;
        });
    }

    @Test void pausedFirstHundredMustNotStarveHealthyDeadlineAndMustBeRetried() {
        var paused=fixture(100);var healthy=fixture(1);
        var ids=new ArrayList<String>();
        var deadline=LocalDateTime.of(2001,1,1,0,0);
        for(var f:List.of(paused,healthy)) db.update("UPDATE tf_session SET purchase_mode='ASYNC' WHERE id=?",f.session());
        db.update("UPDATE tf_async_gate SET phase='READY' WHERE session_id=?",healthy.session());
        for(int i=0;i<100;i++) ids.add(accepted(paused,deadline));
        String last=accepted(healthy,deadline.plusSeconds(1));
        try {
            worker.sweep();worker.sweep();
            assertEquals("REJECTED",requests.get(last,false).state(),"healthy tail must progress while the first 100 remain paused");
            assertEquals("PROCESSING_DEADLINE_EXCEEDED",requests.get(last,false).code());
            assertEquals(0,count("SELECT queued_count FROM tf_async_tier_balance WHERE tier_id=?",healthy.tier()));
            for(String id:ids) assertEquals("ACCEPTED",requests.get(id,false).state());
            db.update("UPDATE tf_async_gate SET phase='READY' WHERE session_id=?",paused.session());
            for(int i=0;i<4;i++) worker.sweep();
            for(String id:ids) assertEquals("REJECTED",requests.get(id,false).state());
            assertEquals(0,count("SELECT queued_count FROM tf_async_tier_balance WHERE tier_id=?",paused.tier()));
            assertEquals(0,count("SELECT COUNT(*) FROM tf_async_slot WHERE session_id IN (?,?)",paused.session(),healthy.session()));
            assertEquals(101,count("SELECT COUNT(*) FROM tf_outbox WHERE session_id IN (?,?) AND event_type='RELEASE'",paused.session(),healthy.session()));
            worker.sweep();
            assertEquals(101,count("SELECT COUNT(*) FROM tf_outbox WHERE session_id IN (?,?) AND event_type='RELEASE'",paused.session(),healthy.session()));
        } finally {
            db.update("UPDATE tf_async_gate SET phase='READY' WHERE session_id=?",paused.session());
            for(int i=0;i<4;i++) worker.sweep();
            // These DB-only fixtures never had a Redis view. Retire their epoch
            // after assertions so the normal publisher can settle obsolete events.
            for(var f:List.of(paused,healthy)) new TransactionTemplate(manager).execute(status -> {
                db.queryForObject("SELECT epoch FROM tf_async_gate WHERE session_id=? FOR UPDATE",Long.class,f.session());
                db.update("UPDATE tf_async_gate SET epoch=epoch+1,phase='PAUSED' WHERE session_id=?",f.session());
                return null;
            });
            for(var f:List.of(paused,healthy)) db.update("UPDATE tf_event SET status='OFF_SALE' WHERE id=?",f.event());
        }
    }
}
