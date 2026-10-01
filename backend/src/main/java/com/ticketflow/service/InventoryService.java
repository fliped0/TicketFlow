package com.ticketflow.service;

import com.ticketflow.mapper.InventoryMapper;
import com.ticketflow.mapper.TradeMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/** Collaborates inside the caller's transaction; never starts a separate transaction. */
@Service
public class InventoryService {
    private final InventoryMapper db;
    public InventoryService(InventoryMapper db) { this.db=db; }
    public int lock(long tier) { return db.lockStock(tier); }
    public void reserve(long tier, long order, LocalDateTime time) {
        TradeMapper.requireOne(db.reserve(tier,time));
        TradeMapper.requireOne(db.logReserve(order,time));
    }
}
