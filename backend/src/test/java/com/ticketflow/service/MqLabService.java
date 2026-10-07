package com.ticketflow.service;

import com.ticketflow.mapper.MqLabMapper;
import com.ticketflow.model.MqLabMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Inbox, ledger and counter commit together; callers ACK only after this returns. */
public class MqLabService {
    private final MqLabMapper db;
    private final TransactionTemplate tx;
    public MqLabService(MqLabMapper db, PlatformTransactionManager manager) {
        this.db=db; tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15);
    }
    public boolean apply(MqLabMessage msg) { return apply(msg,()->{}); }
    public boolean apply(MqLabMessage msg, Runnable beforeCommit) {
        return Boolean.TRUE.equals(tx.execute(status->{
            try { db.claim(msg); }
            catch (DuplicateKeyException duplicate) {
                if (!db.claimedHash(msg.eventId()).equals(msg.hash())) throw new IllegalArgumentException("Event ID payload conflict");
                return false;
            }
            db.effect(msg); beforeCommit.run(); return true;
        }));
    }
}
