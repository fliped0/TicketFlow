package com.ticketflow.mapper;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryMapper {
    private final JdbcTemplate db;
    public InventoryMapper(JdbcTemplate db) { this.db=db; }
    public int lockStock(long tier) {
        var rows=db.queryForList("SELECT available FROM tf_stock WHERE tier_id=? FOR UPDATE",Integer.class,tier);
        if (rows.isEmpty()) throw new IllegalStateException("Missing stock for tier");
        return rows.get(0);
    }
    public int reserve(long tier, LocalDateTime time) { return db.update("UPDATE tf_stock SET available=available-1,reserved=reserved+1,updated_at=? WHERE tier_id=? AND available>=1",time.toString().replace('T',' '),tier); }
    public int logReserve(long order, LocalDateTime time) { return db.update("INSERT INTO tf_stock_log(order_id,movement,delta_available,delta_reserved,delta_sold,created_at) VALUES(?,'RESERVE',-1,1,0,?)",order,time.toString().replace('T',' ')); }
}
