package com.ticketflow.model;

import com.ticketflow.service.TradeExecutor;
import java.util.UUID;

/** Synthetic lab account operation; never a customer order or public API. */
public record MqLabMessage(int schemaVersion, String eventId, String accountId, int delta, int attempt) {
    public MqLabMessage {
        if (schemaVersion != 1 || delta < 1 || delta > 1000 || attempt < 0 || attempt > 3)
            throw new IllegalArgumentException("Invalid lab message");
        UUID.fromString(eventId); UUID.fromString(accountId);
    }
    public static MqLabMessage create(String account) { return new MqLabMessage(1,UUID.randomUUID().toString(),account,1,0); }
    public String hash() { return TradeExecutor.hash("LAB:v1\naccount="+accountId+"\ndelta="+delta); }
    public MqLabMessage retry() { return new MqLabMessage(schemaVersion,eventId,accountId,delta,attempt+1); }
}
