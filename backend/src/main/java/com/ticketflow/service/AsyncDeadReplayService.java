package com.ticketflow.service;

import com.ticketflow.mapper.*;
import com.ticketflow.model.entity.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AsyncDeadReplayService {
    private final AsyncTransactions tx;
    private final AsyncGateMapper gates;
    private final TradeMapper users;
    private final AsyncRequestMapper requests;
    private final OutboxMapper outbox;
    private final AsyncOperationsMapper operations;
    private final AsyncEvents events;
    private final DatabaseClock clock;
    private final JsonMapper json;
    public AsyncDeadReplayService(AsyncTransactions tx,AsyncGateMapper gates,TradeMapper users,AsyncRequestMapper requests,OutboxMapper outbox,AsyncOperationsMapper operations,AsyncEvents events,DatabaseClock clock,JsonMapper json) {
        this.tx=tx;this.gates=gates;this.users=users;this.requests=requests;this.outbox=outbox;this.operations=operations;this.events=events;this.clock=clock;this.json=json;
    }
    /** ACK permission only after the replacement outbox and replay receipt commit. */
    public boolean replay(AsyncMessage message) {
        var envelope=outbox.get(message.eventId());var route=requests.get(message.requestId(),false);
        if(envelope==null || route==null || !"BROKER".equals(envelope.destination()) || !route.id().equals(envelope.aggregate()) || !json.readValue(envelope.payload(),AsyncMessage.class).equals(message))return false;
        return tx.execute(()->{
            var gate=gates.lock(route.sessionId(),false);users.lockOwner(route.userId());var r=requests.get(route.id(),true);
            if(operations.replayed(message.eventId()))return true;
            if(!r.terminal()) {
                if(!"READY".equals(gate.phase()) || gate.epoch()!=r.epoch())return false;
                var now=clock.nowUtc();
                if("PROCESSING".equals(r.state()) && now.isBefore(r.leaseUntil()))return false;
                operations.retry(r.id());events.broker(requests.get(r.id(),true),now);
            }
            operations.receipt(message.eventId(),r.id());return true;
        });
    }
}
