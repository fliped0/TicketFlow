package com.ticketflow.service;

import com.ticketflow.mapper.TradeMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class DatabaseClock {
    private final TradeMapper db;
    public DatabaseClock(TradeMapper db) { this.db=db; }
    public LocalDateTime nowUtc() { return db.now(); }
}
