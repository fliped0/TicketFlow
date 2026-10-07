package com.ticketflow.mapper;

import com.ticketflow.model.MqLabMessage;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only tables in the dedicated test DB. No production/Flyway changes. */
public class MqLabMapper {
    private final JdbcTemplate db;
    private final String prefix;
    public MqLabMapper(JdbcTemplate db, String prefix) {
        if (!prefix.matches("b8_[a-f0-9]{12}_")) throw new IllegalArgumentException("Unsafe lab prefix");
        if (!"ticketflow_test".equals(db.queryForObject("SELECT DATABASE()",String.class)))
            throw new IllegalStateException("Lab requires ticketflow_test");
        this.db=db; this.prefix=prefix;
    }
    public void createTables() {
        db.execute("CREATE TABLE "+prefix+"account(id CHAR(36) PRIMARY KEY,effect_count INT NOT NULL DEFAULT 0,CHECK(effect_count>=0)) ENGINE=InnoDB");
        db.execute("CREATE TABLE "+prefix+"inbox(event_id CHAR(36) PRIMARY KEY,payload_hash CHAR(64) NOT NULL,account_id CHAR(36) NOT NULL,FOREIGN KEY(account_id) REFERENCES "+prefix+"account(id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE "+prefix+"effect(event_id CHAR(36) PRIMARY KEY,account_id CHAR(36) NOT NULL,delta INT NOT NULL,CHECK(delta>0),FOREIGN KEY(event_id) REFERENCES "+prefix+"inbox(event_id),FOREIGN KEY(account_id) REFERENCES "+prefix+"account(id)) ENGINE=InnoDB");
    }
    public void account(String account) { db.update("INSERT INTO "+prefix+"account(id) VALUES(?)",account); }
    public void claim(MqLabMessage msg) { db.update("INSERT INTO "+prefix+"inbox(event_id,payload_hash,account_id) VALUES(?,?,?)",msg.eventId(),msg.hash(),msg.accountId()); }
    public String claimedHash(String event) { return db.queryForObject("SELECT payload_hash FROM "+prefix+"inbox WHERE event_id=? FOR UPDATE",String.class,event); }
    public void effect(MqLabMessage msg) {
        TradeMapper.requireOne(db.update("UPDATE "+prefix+"account SET effect_count=effect_count+? WHERE id=?",msg.delta(),msg.accountId()));
        db.update("INSERT INTO "+prefix+"effect(event_id,account_id,delta) VALUES(?,?,?)",msg.eventId(),msg.accountId(),msg.delta());
    }
    public int effects(String account) { return db.queryForObject("SELECT effect_count FROM "+prefix+"account WHERE id=?",Integer.class,account); }
    public int ledger(String account) { return db.queryForObject("SELECT COUNT(*) FROM "+prefix+"effect WHERE account_id=?",Integer.class,account); }
    public int inbox(String account) { return db.queryForObject("SELECT COUNT(*) FROM "+prefix+"inbox WHERE account_id=?",Integer.class,account); }
}
